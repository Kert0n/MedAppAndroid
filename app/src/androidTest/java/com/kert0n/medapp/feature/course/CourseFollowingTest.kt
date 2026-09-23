package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Курс следует за коробкой **у одного владельца** (PLAN D5, C1 «`followBox` как владелец
 * реакций»): три двери — пересчёт человека, укладка снимка, ответ сервера на команду — дают одно и
 * то же следствие, и проверяется оно **одной функцией утверждений**. Пока реакция жила в DAO и в
 * сценарии порознь, у снимка не было «прошлого раньше зажима», а у сценария — правила «бронь
 * только коробке, отвечающей серверу».
 */
@RunWith(AndroidJUnit4::class)
class CourseFollowingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val shelf get() = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        database.packageRepository().add(
            pack(id = PACK, medKit = shelf, quantity = tablets("20"), form = TABLET_FORM),
            PackageSyncState(PACK, ResourceVersion(3), ResourceVersion(1), syncedAt = now)
        )
    }

    @After
    fun tearDown() = database.close()

    /** Лечение по две таблетки с [start], 10 доз из [PACK]; бронь на 20 уехала командой начала. */
    private suspend fun treated(
        start: LocalDate = LocalDate.of(2027, 3, 10),
        sources: List<Pair<Uuid, Int>> = listOf(PACK to 10),
        scenarios: Scenarios = this.scenarios
    ): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10))
            ) + sources.map { (id, doses) -> CourseDrafting.Edit.Attach(id, Doses(doses)) }
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private fun snapshot(quantity: String, version: Long = 4) = PackageSnapshot(
        pack(id = PACK, medKit = shelf, quantity = tablets(quantity), form = TABLET_FORM, claims = Claims(BigDecimal("20"), BigDecimal("20"))),
        PackageSyncState(PACK, ResourceVersion(version), ResourceVersion(2), syncedAt = now)
    )

    private suspend fun plan(id: Uuid): Course = requireNotNull(database.courseRepository().findPlan(id))

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    /** Три двери, одна коробка: осталось 12 → шесть доз, событие сокращения, бронь на 12, план прежний. */
    private suspend fun assertFollowed(id: Uuid, before: Course) {
        val after = plan(id)
        assertEquals(Doses(6), after.sources.single().allocatedDoses)
        assertEquals("расписание, доза и даты не меняются", before.prescription, after.prescription)
        assertEquals(before.revision.next(), after.revision)
        val reduction = database.courseRepository().reductionsSince(id, now.minusSeconds(1)).single()
        assertEquals(Doses(10), reduction.coveredBefore)
        assertEquals(Doses(6), reduction.coveredAfter)
        assertTrue("бронь разницей не уехала: ${commands()}", PackageSyncCommand.SetClaim(PACK, tablets("12")) in commands())
        assertEquals(id, database.courseRepository().courseHolding(PACK))
    }

    @Test
    fun aRecountByTheHumanIsTheSameConsequence() = runTest {
        val id = treated()
        val before = plan(id)

        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("12")))

        assertFollowed(id, before)
    }

    @Test
    fun aSnapshotIsTheSameConsequence() = runTest {
        val id = treated()
        val before = plan(id)

        database.snapshotStorage().lay(ServerSnapshot(mapOf(SHARED_KIT to 2L), listOf(snapshot("12")), emptySet(), emptySet(), emptySet(), setOf(PACK)), now)

        assertFollowed(id, before)
    }

    @Test
    fun anAnswerIsTheSameConsequence() = runTest {
        val id = treated()
        val before = plan(id)
        val claim = (database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        database.queueStorage().take(claim.id, null, now)

        database.queueStorage().settle(claim.id, Delivery.Applied(PackageState.Present(snapshot("12"))).settlement(claim.command), now)

        assertFollowed(id, before)
    }

    /**
     * **Конец коробки — то же изменение, только самое резкое** (C1 «Конец коробки — у владельца
     * реакции»). Сервер перестал знать коробку — сосед выбросил её: лечение теряет источник её же
     * переходом, обеспеченных доз стало меньше — событие, о котором говорят человеку, назначение
     * снято. Пока конец шёл через расширение DAO, источник отсоединялся молча: сокращения не было,
     * и `COVERAGE_SHORT` не приходил.
     */
    @Test
    fun aLostBoxIsTheSameConsequence() = runTest {
        val id = treated()
        val before = plan(id)

        database.snapshotStorage().lay(ServerSnapshot(mapOf(SHARED_KIT to 2L), emptyList(), emptySet(), setOf(PACK), emptySet(), emptySet()), now)

        val after = plan(id)
        assertTrue("источник пережил коробку: ${after.sources}", after.sources.isEmpty())
        assertEquals("расписание, доза и даты не меняются", before.prescription, after.prescription)
        val reductions = database.courseRepository().reductionsSince(id, now.minusSeconds(1))
        assertEquals("о потере обеспечения не сказано: $reductions", listOf(Doses(10) to Doses(0)), reductions.map { it.coveredBefore to it.coveredAfter })
        assertNull(database.courseRepository().courseHolding(PACK))
        assertNull(database.packageRepository().find(PACK))
    }

    /**
     * **«Принёс домой» — тоже изменение коробки**: полка к снятию подтвердила 12, дома она стала
     * местной с 12 — и лечение следует за новым числом: шесть доз, событие сокращения. Пока ответ
     * на «унёс домой» менял число мимо владельца, выделение оставалось на 20 таблетках.
     */
    @Test
    fun bringingHomeIsTheSameConsequence() = runTest {
        database.medKits().upsert(medKit(id = HOME_KIT).toMedKitStorageEntity())
        val id = treated()
        val before = plan(id)
        assertEquals(PackageRelocation.Outcome.MOVED, scenarios.packageRelocation.move(PACK, HOME_KIT))
        val withdraw = database.syncOperations().all()
            .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation }
            .single { it.command is PackageSyncCommand.Withdraw }
        database.queueStorage().take(withdraw.id, snapshot("12"), now)

        database.queueStorage().settle(withdraw.id, Delivery.Applied(PackageState.Gone).settlement(withdraw.command), now)

        val after = plan(id)
        assertEquals(tablets("12"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertEquals("лечение не последовало за принесённой домой коробкой", Doses(6), after.sources.single().allocatedDoses)
        assertEquals("расписание, доза и даты не меняются", before.prescription, after.prescription)
        val reduction = database.courseRepository().reductionsSince(id, now.minusSeconds(1)).single()
        assertEquals(Doses(10), reduction.coveredBefore)
        assertEquals(Doses(6), reduction.coveredAfter)
    }

    /**
     * Прошлое — раньше, чем лечение трогают (F4), и у снимка тоже: вчерашний пункт без ответа
     * становится пропуском, и потребность считается без него. Пока укладка звала расширение DAO,
     * снимок этого не делал, и зажим шёл по потребности, в которой вчерашний неответ ещё числился
     * будущим.
     */
    @Test
    fun theSnapshotMarksThePastBeforeClamping() = runTest {
        // Лечение началось вчера, и вчера же его завели: сегодняшняя укладка застаёт неответ.
        val id = treated(start = LocalDate.of(2027, 3, 9), scenarios = Scenarios(database, Instant.parse("2027-03-09T05:00:00Z")))
        val yesterday = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        assertEquals(IntakeStatus.PLANNED, yesterday.status)

        database.snapshotStorage().lay(ServerSnapshot(mapOf(SHARED_KIT to 2L), listOf(snapshot("12")), emptySet(), emptySet(), emptySet(), setOf(PACK)), now)

        assertEquals(IntakeStatus.MISSED, requireNotNull(database.intakeRepository().find(yesterday.id)).status)
        assertEquals(Doses(6), plan(id).sources.single().allocatedDoses)
    }

    /**
     * Бронь едет на полку **своей** коробки, и местной полке не едет ничего: у местной коробки
     * серверной брони нет, и команда ей — мусор в очереди. Лечение из общей и местной коробок:
     * счёт доз мимо плана уменьшает выделения обеих, команда — одна, и она о [PACK].
     */
    @Test
    fun claimsGoToTheShelfOfTheirOwnBoxAndNotToALocalOne() = runTest {
        database.packageRepository().add(pack(id = OTHER_PACK, medKit = medKit(id = HOME_KIT).ref, quantity = tablets("20"), form = TABLET_FORM))
        val id = treated(sources = listOf(PACK to 5, OTHER_PACK to 5))
        val before = plan(id)
        val started = commands()

        scenarios.courseOffPlanCounting.set(id, before.revision, Doses(8))

        val after = plan(id)
        assertEquals(Doses(2), after.allocatedDosesTotal)
        val announced = commands().drop(started.size).filterIsInstance<PackageSyncCommand>()
        assertTrue("команда брони местной коробке: $announced", announced.none { it.packageId == OTHER_PACK })
        assertTrue("бронь общей коробке не уехала: $announced", announced.any { it.packageId == PACK })
    }
}
