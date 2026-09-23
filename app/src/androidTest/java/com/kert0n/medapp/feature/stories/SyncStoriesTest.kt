package com.kert0n.medapp.feature.stories

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.FakeServer
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.Mechanisms
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозные истории с сервером без экранов (PLAN J2.3, J2.8, локальная половина J2.11) — против
 * сервера в памяти: настоящего не попросишь уронить ответ ровно после применения или отказать по
 * заказу. Общая полка с коробкой известна серверу; заход — тот же `Synchronization`, что зовёт
 * вход в приложение. Утверждения — на чтениях экранов (U2).
 */
@RunWith(AndroidJUnit4::class)
class SyncStoriesTest {

    private lateinit var database: MedAppDatabase
    private lateinit var server: FakeServer
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val shelf get() = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        server = FakeServer()
        server.shelf(SHARED_KIT)
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
    }

    @After
    fun tearDown() = database.close()

    /** Общая коробка на 20 таблеток, известная серверу с той же версией, что у нас. */
    private suspend fun sharedBox(quantity: String = "20") {
        server.drug(PACK, SHARED_KIT, quantity = quantity)
        database.packageRepository().add(
            pack(id = PACK, medKit = shelf, quantity = tablets(quantity), form = TABLET_FORM),
            PackageSyncState(PACK, ResourceVersion(1), ResourceVersion(1), syncedAt = now)
        )
    }

    private suspend fun treated(scenarios: Scenarios, pkg: Uuid, doses: Int): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(doses)),
                CourseDrafting.Edit.Attach(pkg, Doses(doses))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun statuses(): List<SyncOperationStatus> =
        database.syncOperations().all().map { (it.toDomain(com.kert0n.medapp.fixture.VOCABULARY) as StoredSyncOperation.Readable).operation.status }

    /**
     * **J2.3 Нехватка.** Сосед принял из общей коробки → заход → обеспечение сократилось, пришло
     * уведомление механизмом, **даты и доза курса прежние**. Своя половина: офлайн пересчитал коробку
     * с 20 до 5 → обеспечение сразу от пяти; разовый приём поверх — ещё на дозу; чужая бронь из
     * доступного вычтена.
     */
    @Test
    fun aShortageAfterANeighboursIntakeReducesCoverageNotTheSchedule() = runBlocking {
        sharedBox()
        val scenarios = Scenarios(database, now)
        val course = treated(scenarios, PACK, 10)
        val before = requireNotNull(database.courseRepository().findPlan(course))
        Mechanisms(scenarios, now).use { mechanisms ->
            // Бронь на 20 уехала на сервер; сосед за это время выпил 14 — на сервере осталось 6.
            server.synchronization(database, clock).synchronize()
            assertEquals("20", server.drugs.getValue(PACK).mine?.toPlainString())
            server.drugs.getValue(PACK).quantity = BigDecimal("6")
            server.drugs.getValue(PACK).version++

            val round = server.synchronization(database, clock).synchronize()
            assertTrue("снимок не лёг: ${round.snapshot}", round.snapshot is com.kert0n.medapp.queue.SnapshotApplier.Outcome.Applied)

            // Экран 14/16: обеспечено три дозы из десяти, первый необеспеченный назван; план прежний.
            val coverage = requireNotNull(database.courseRepository().observeCoverage(course).first())
            assertEquals(Doses(10), coverage.requiredDoses)
            assertEquals(Doses(3), coverage.coveredDoses)
            assertNotNull(coverage.firstUncoveredAt)
            val after = requireNotNull(database.courseRepository().findPlan(course))
            assertEquals(before.prescription, after.prescription)
            // Уведомление о сокращении — механизмом: событие → сверка по сигналу → показ.
            mechanisms.await("сообщение о сокращении показано") { scenarios.notifier.shown.any { it.kind == NotificationKind.COVERAGE_SHORT } }
            // Бронь разницей встала в очередь внутри укладки снимка; следующий заход её везёт.
            server.synchronization(database, clock).synchronize()
            // Экран 28: очередь без отказов — бронь разницей уехала и применена.
            assertEquals("очередь: ${database.syncOperations().all().map { it.operation.status.toString() + ":" + it.operation.lastError + ":" + it.operation.kind }}", true, statuses().all { it == SyncOperationStatus.APPLIED })
            assertEquals("6", server.drugs.getValue(PACK).mine?.toPlainString())
        }

        // Своя половина, на местной коробке: пересчёт 20 → 5 даёт обеспечение от пяти сразу, а
        // разовый приём поверх незакрытого пересчёта зажимает ещё на дозу.
        val home = Uuid.random()
        database.packageRepository().add(pack(id = home, medKit = medKit(id = HOME_KIT).ref, quantity = tablets("20"), form = TABLET_FORM))
        val local = treated(scenarios, home, 10)
        assertEquals(PackageAdjusting.Outcome.ADJUSTED, scenarios.packageAdjusting.adjust(home, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("5"))))
        assertEquals(Doses(2), requireNotNull(database.courseRepository().observeCoverage(local).first()).coveredDoses)
        assertTrue(scenarios.unplannedIntakeRecording.record(home, dose("2"), now, acknowledged = true) is UnplannedIntakeRecording.Outcome.Recorded)
        assertEquals(Doses(1), requireNotNull(database.courseRepository().observeCoverage(local).first()).coveredDoses)
        // Чужая бронь вычтена из доступного мне: соседи забронировали 4 из шести оставшихся.
        server.drugs.getValue(PACK).total = BigDecimal("10")
        server.drugs.getValue(PACK).claimsVersion++
        server.synchronization(database, clock).synchronize()
        assertEquals(tablets("2"), requireNotNull(database.packageRepository().observe(PACK).first()).availability.availableToMe)
    }

    /**
     * **J2.8 Потеря связи посреди приёма** и локальная половина **J2.11**. Приём офлайн → «перезапуск»
     * — новый заход над той же базой → связь: сервер применил расход, но ответ потерялся → повтор
     * тем же `syncId` → расход применён **один раз**, операция `APPLIED`, остаток серверный. Второй
     * случай: сервер отвергает расход — факт цел, расхождение `REMOTE_REFUSED` видно.
     */
    @Test
    fun anIntakeSurvivesALostConnectionAndIsAppliedOnce() = runBlocking {
        sharedBox()
        val scenarios = Scenarios(database, now)
        val course = treated(scenarios, PACK, 5)
        server.synchronization(database, clock).synchronize()
        val first = database.intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>().minBy { it.plannedAt }

        server.offline = true
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        val failed = server.synchronization(database, clock).synchronize()
        assertTrue(failed.queue.settled == 0)
        assertEquals(IntakeStatus.TAKEN, requireNotNull(database.intakeRepository().find(first.id)).status)
        assertEquals(IntakeAccounting.PENDING, database.intakeRepository().syncStateOf(first.id)?.accounting)
        // Экран 6: остаток уже уменьшен для человека, серверное число прежнее.
        assertEquals(tablets("18"), requireNotNull(database.packageRepository().observe(PACK).first()).availability.availableToMe)

        // Связь вернулась, но ответ на расход потерян после применения; «перезапуск» — новый заход.
        server.offline = false
        val consume = database.syncOperations().all().map { (it.toDomain(com.kert0n.medapp.fixture.VOCABULARY) as StoredSyncOperation.Readable).operation }
            .first { it.status != SyncOperationStatus.APPLIED }
        server.loseAnswerFor += consume.id
        // Повтор после сбоя — не сейчас же: заход идёт, когда срок повтора наступил.
        server.synchronization(database, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)).synchronize()
        assertEquals("сервер применил расход, хоть ответ и потерян", "18", server.drugs.getValue(PACK).quantity.toPlainString())
        assertTrue(statuses().any { it != SyncOperationStatus.APPLIED })

        server.synchronization(database, Clock.fixed(now.plusSeconds(180), ZoneOffset.UTC)).synchronize()

        assertEquals("списано дважды", "18", server.drugs.getValue(PACK).quantity.toPlainString())
        assertTrue(statuses().all { it == SyncOperationStatus.APPLIED })
        assertEquals(IntakeAccounting.REMOTE_APPLIED, database.intakeRepository().syncStateOf(first.id)?.accounting)
        assertEquals(tablets("18"), requireNotNull(database.packageRepository().observe(PACK).first()).quantity)

        // Второй случай: сервер отвергает расход — факт цел, расхождение видно.
        val second = database.intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>().filter { it.status == IntakeStatus.PLANNED }.minBy { it.plannedAt }
        server.refuseConsumptionOf += PACK
        scenarios.intakeConfirmation.confirm(second.id, PACK, dose("2"), now.plusSeconds(200)).confirmed()
        server.synchronization(database, Clock.fixed(now.plusSeconds(400), ZoneOffset.UTC)).synchronize()

        assertEquals(IntakeStatus.TAKEN, requireNotNull(database.intakeRepository().find(second.id)).status)
        assertEquals(IntakeAccounting.REMOTE_REFUSED, database.intakeRepository().syncStateOf(second.id)?.accounting)
        assertTrue(database.queueRepository().observeOutstanding().first().any { (it as? StoredSyncOperation.Readable)?.operation?.status == SyncOperationStatus.REFUSED })
    }
}
