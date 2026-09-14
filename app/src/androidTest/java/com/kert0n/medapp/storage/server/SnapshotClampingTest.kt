package com.kert0n.medapp.storage.server

import com.kert0n.medapp.feature.course.SourceEditing
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Чужое изменение, пришедшее снимком или ответом на команду, сокращает обеспечение, а не курс:
 * выделения зажимаются под доступное мне той же транзакцией, бронь уезжает разницей, расписание
 * и доза прежние; снимок, согласный с нами, не пишет ничего (PLAN C1 «Нехватка», D5, E4).
 */
@RunWith(AndroidJUnit4::class)
class SnapshotClampingTest {

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

    /** Лечение по две таблетки, 10 доз, все десять выделены из [PACK]; бронь на 20 уехала командой начала. */
    private suspend fun treated(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(10))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    /** Снимок коробки с сервера: столько осталось, столько заявлено всеми и мной. */
    private fun snapshot(quantity: String, total: String = "20", mine: String = "20", version: Long = 4) = PackageSnapshot(
        pack(id = PACK, medKit = shelf, quantity = tablets(quantity), form = TABLET_FORM, claims = Claims(BigDecimal(total), BigDecimal(mine))),
        PackageSyncState(PACK, ResourceVersion(version), ResourceVersion(2), syncedAt = now)
    )

    private suspend fun lay(snapshot: PackageSnapshot) = database.snapshotStorage().lay(
        ServerSnapshot(mapOf(SHARED_KIT to 2L), listOf(snapshot), emptySet(), emptySet(), emptySet(), setOf(PACK)),
        now
    )

    private suspend fun plan(id: Uuid): Course = requireNotNull(database.courseRepository().findPlan(id))

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    /** Сосед принял восемь: осталось 12 — шесть доз, выделение 10 → 6, бронь на 12 уезжает; доза и расписание прежние. */
    @Test
    fun aNeighboursIntakeClampsTheAllocationAndSendsTheNewClaim() = runTest {
        val id = treated()
        val before = plan(id)

        lay(snapshot("12"))

        val after = plan(id)
        assertEquals(Doses(6), after.sources.single().allocatedDoses)
        assertEquals(before.prescription, after.prescription)
        assertEquals(before.revision.next(), after.revision)
        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("12"))), commands().drop(1))

        // Повтор того же снимка ничего не меняет: редакция стоит, второй команды нет.
        lay(snapshot("12", version = 5))
        assertEquals(after.revision, plan(id).revision)
        assertEquals(2, commands().size)
    }

    /** Снимок с большим числом выделение не растёт: автоматического увеличения нет. */
    @Test
    fun aLargerSnapshotDoesNotGrowTheAllocation() = runTest {
        val id = treated()
        val before = plan(id)

        lay(snapshot("25"))

        assertEquals(Doses(10), plan(id).sources.single().allocatedDoses)
        assertEquals(before.revision, plan(id).revision)
        assertEquals(1, commands().size)
    }

    /** Ответ сервера на команду кладёт снимок той же дверью — и зажимает так же. */
    @Test
    fun anAnswerLaysTheSnapshotThroughTheSameDoorAndClamps() = runTest {
        val id = treated()
        val claim = (database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        database.queueStorage().take(claim.id, null, now)

        database.queueStorage().settle(
            claim.id,
            Delivery.Applied(PackageState.Present(snapshot("12"))).settlement(claim.command),
            now
        )

        assertEquals(Doses(6), plan(id).sources.single().allocatedDoses)
        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("12"))), commands().drop(1))
    }

    /**
     * Сосед сменил единицу коробки: источник отключён с причиной, выделение — ноль, коробку курс не
     * держит, бронь снята; курс идёт, обеспечение читается (красная проверка: без `fault` —
     * `dosesIn` бросает на чужой единице). Вернули таблетки — причина снята, выделение остаётся нулём.
     */
    @Test
    fun aChangedUnitDisablesTheSourceWithAReasonAndAReturnRestoresIt() = runTest {
        val id = treated()

        lay(PackageSnapshot(
            pack(id = PACK, medKit = shelf, quantity = millilitres("200"), form = TABLET_FORM, claims = Claims(BigDecimal("0"), BigDecimal("0"))),
            PackageSyncState(PACK, ResourceVersion(4), ResourceVersion(2), syncedAt = now)
        ))

        val faulted = plan(id).sources.single()
        assertEquals(CourseSource.Fault.UNIT_MISMATCH, faulted.fault)
        assertEquals(Doses(0), faulted.allocatedDoses)
        assertNull(database.courseRepository().courseHolding(PACK))
        assertEquals(listOf(PackageSyncCommand.ReleaseClaim(PACK)), commands().drop(1))
        assertEquals(TABLET_FORM, plan(id).form)
        assertEquals(Doses(0), requireNotNull(database.courseRepository().observeCoverage(id).first()).coveredDoses)

        lay(snapshot("20", total = "0", mine = "0", version = 5))

        val restored = plan(id).sources.single()
        assertNull(restored.fault)
        assertEquals(Doses(0), restored.allocatedDoses)
        assertEquals(id, database.courseRepository().courseHolding(PACK))
    }

    /** Смена формы отключает так же — своей причиной. */
    @Test
    fun aChangedFormDisablesTheSource() = runTest {
        val id = treated()

        lay(PackageSnapshot(
            pack(id = PACK, medKit = shelf, quantity = tablets("20"), form = CAPSULE_FORM, claims = Claims(BigDecimal("0"), BigDecimal("0"))),
            PackageSyncState(PACK, ResourceVersion(4), ResourceVersion(2), syncedAt = now)
        ))

        assertEquals(CourseSource.Fault.FORM_MISMATCH, plan(id).sources.single().fault)
    }

    private suspend fun reductions(id: Uuid) = database.courseRepository().observeReductions(id).first()

    /**
     * Сокращение — событие: пишется при зажиме снимком и пересчётом человека, было и стало; повтор
     * снимка и снимок с большим числом второго события не дают (PLAN D5).
     */
    @Test
    fun aReductionIsRecordedOnceForEachShrinkage() = runTest {
        val id = treated()

        lay(snapshot("12"))
        lay(snapshot("12", version = 5))
        lay(snapshot("25", version = 6))

        val fromNeighbour = reductions(id).single()
        assertEquals(Doses(10), fromNeighbour.coveredBefore)
        assertEquals(Doses(6), fromNeighbour.coveredAfter)
        assertEquals(PACK, fromNeighbour.packageId)

        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("25"), actual = tablets("8")))

        // Оба события — одним мигом фиксированных часов, и порядок по времени между ними не определён.
        val recorded = reductions(id)
        assertEquals(2, recorded.size)
        val fromMe = recorded.single { it.coveredBefore == Doses(6) && it.coveredAfter == Doses(4) }
        assertEquals(PACK, fromMe.packageId)
    }

    /** Мой приём по плану уменьшает выделение на принятое — это не сокращение. */
    @Test
    fun myOwnPlannedIntakeIsNotAReduction() = runTest {
        val id = treated()
        val first = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }

        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()

        assertEquals(emptyList<CoverageReduction>(), reductions(id))
    }

    /**
     * Зажим тронул и вторую пачку курса — с местной полки: команда ей не ставится вовсе, а команда
     * первой едет полке первой (красная проверка: слать всё полке коробки из снимка — команда
     * местной коробке встала бы в очередь общей полки).
     */
    @Test
    fun eachClaimCommandGoesToItsOwnPackagesShelf() = runTest {
        val id = treated()
        val home = Uuid.random()
        database.packageRepository().add(pack(id = home, name = "Домашняя", quantity = tablets("20"), form = TABLET_FORM))
        val plan = plan(id)
        // Вторая пачка сверх потребности: зажим снимет лишнее с конца — с неё.
        scenarios.sourceEditing.save(id, plan.revision, listOf(SourceEditing.Source(PACK, Doses(10)), SourceEditing.Source(home, Doses(2))))
        val before = database.syncOperations().all().size

        lay(snapshot("12"))

        val after = database.syncOperations().all().drop(before)
            .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation to it.operation.medKitId }
        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("12"))), after.map { it.first.command })
        assertEquals(listOf(SHARED_KIT), after.map { it.second })
        assertEquals(Doses(6), plan(id).sources.first { it.pkg.id == PACK }.allocatedDoses)
    }

    /** Форму сменил сам человек: дверь та же, источник отключён, из него больше не принимают. */
    @Test
    fun aFormChangedByTheHumanDisablesTheSourceToo() = runTest {
        val id = treated()
        val facts = requireNotNull(database.packageRepository().find(PACK)).facts

        scenarios.packageDescribing.describe(PACK, facts.copy(shared = facts.shared.copy(form = CAPSULE_FORM)))

        assertEquals(CourseSource.Fault.FORM_MISMATCH, plan(id).sources.single().fault)
        val first = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        assertEquals(
            com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.Rejected(com.kert0n.medapp.domain.intake.IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE),
            scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now)
        )
        val edit = scenarios.sourceEditing.save(id, plan(id).revision, listOf(SourceEditing.Source(PACK, Doses(3))))
        assertEquals(SourceEditing.Outcome.Rejected(com.kert0n.medapp.domain.course.CourseRejected.Reason.FORM_MISMATCH), edit)
    }
}
