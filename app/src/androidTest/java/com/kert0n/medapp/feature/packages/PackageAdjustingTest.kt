package com.kert0n.medapp.feature.packages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Пересчёт и утилизация меняют число (PLAN D3, E1, C1). Своя полка — переход к прочитанному, ноль
 * кончает коробку; полка, отвечающая серверу, — `CorrectStock(seen, actual)` и пометка, а число
 * не трогается. Лечение, державшее коробку, зажимается под новую доступность, и на общей полке
 * бронь уезжает разницей.
 */
@RunWith(AndroidJUnit4::class)
class PackageAdjustingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var adjusting: PackageAdjusting

    private val sync = PackageSyncState(PACK, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        adjusting = Scenarios(database, LATER).packageAdjusting
    }

    @After
    fun tearDown() = database.close()

    private suspend fun local() {
        database.packageRepository().add(pack(quantity = tablets("20"), form = TABLET_FORM))
    }

    private suspend fun shared() {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM),
            sync
        )
    }

    /** Курс держит коробку: десять доз по две таблетки — ровно двадцать. */
    private suspend fun holdByACourse() {
        val plan = activeCourse(sources = listOf(source(PACK, 10)), totalDoses = 10)
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
    }

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    private suspend fun allocated(): Doses? =
        database.courseRepository().findPlan(COURSE)?.sources?.firstOrNull { it.pkg.id == PACK }?.allocatedDoses

    @Test
    fun aLocalRecountReplacesTheNumberAndClampsTheCourse() = runTest {
        local()
        holdByACourse()

        val outcome = adjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("7")))

        assertEquals(PackageAdjusting.Outcome.ADJUSTED, outcome)
        assertEquals(tablets("7"), database.packageRepository().find(PACK)?.quantity)
        // Семь таблеток — три целых дозы по две: выделение зажато, редакция выросла.
        assertEquals(Doses(3), allocated())
        assertEquals(Revision(2), database.courseRepository().findPlan(COURSE)?.revision)
        assertTrue(commands().isEmpty())
    }

    @Test
    fun aLocalDisposalToZeroEndsTheBoxAndTheCourseLosesIt() = runTest {
        local()
        holdByACourse()

        val outcome = adjusting.adjust(PACK, PackageAdjusting.Action.Dispose(seen = tablets("20"), amount = tablets("25")))

        assertEquals(PackageAdjusting.Outcome.ENDED, outcome)
        assertNull(database.packageRepository().find(PACK))
        assertEquals(emptyList<Uuid>(), database.courses().sourcePackagesOf(COURSE))
        assertTrue(commands().isEmpty())
    }

    /** На общей полке число не трогается: уезжает разница, а зажим идёт под проекцию. */
    @Test
    fun aSharedRecountAnnouncesTheDifferenceMarksTheBoxAndClampsTheClaim() = runTest {
        shared()
        holdByACourse()

        val outcome = adjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("7")))

        assertEquals(PackageAdjusting.Outcome.ADJUSTED, outcome)
        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(tablets("20"), stored.quantity)
        assertEquals(PackageStatus.CHANGING, stored.status)
        assertEquals(tablets("7"), database.packageRepository().observe(PACK).first()?.availability?.effective)
        assertEquals(Doses(3), allocated())
        assertEquals(
            listOf(
                PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("7")),
                PackageSyncCommand.SetClaim(PACK, tablets("6"))
            ),
            commands()
        )
        assertEquals(sync, database.packageRepository().observeSyncState(PACK).first())
    }

    /**
     * Пересчёт 20 → 15 ещё не доехал, а человек выбросил ещё две: в коробке 13, и зажим идёт под
     * 13 — под всю очередь, как в проекции, а не под последнюю разницу поверх подтверждённых 20.
     *
     * Красная проверка: зажимать по одной последней разнице — 18 таблеток, и выделение остаётся
     * семью дозами после первого пересчёта.
     */
    @Test
    fun aSecondSharedAdjustmentClampsUnderTheWholeQueue() = runTest {
        shared()
        holdByACourse()

        adjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("15")))
        adjusting.adjust(PACK, PackageAdjusting.Action.Dispose(seen = tablets("15"), amount = tablets("2")))

        assertEquals(tablets("13"), database.packageRepository().observe(PACK).first()?.availability?.effective)
        assertEquals(Doses(6), allocated())
    }

    @Test
    fun aSharedDisposalIsTheSameRecountWithLess() = runTest {
        shared()

        adjusting.adjust(PACK, PackageAdjusting.Action.Dispose(seen = tablets("20"), amount = tablets("3")))

        assertEquals(listOf(PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("17"))), commands())
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
    }

    /** Коробку, которая кончится по ответу полки, не зажимают: её потеряет дверь конца. */
    @Test
    fun aSharedRecountToZeroLeavesTheCourseToTheAnswer() = runTest {
        shared()
        holdByACourse()

        adjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("0")))

        assertEquals(Doses(10), allocated())
        assertEquals(listOf(PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("0"))), commands())
        assertEquals(PackageStatus.CHANGING, database.packageRepository().find(PACK)?.status)
    }

    @Test
    fun aBoxWaitingForItsRemovalIsNotAdjusted() = runTest {
        local()
        database.packageRepository().mark(PACK, PackageStatus.REMOVING)

        assertEquals(PackageAdjusting.Outcome.UNUSABLE, adjusting.adjust(PACK, PackageAdjusting.Action.Recount(tablets("20"), tablets("7"))))
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
    }

    @Test
    fun aBoxThatIsGoneSaysSo() = runTest {
        assertEquals(PackageAdjusting.Outcome.GONE, adjusting.adjust(Uuid.random(), PackageAdjusting.Action.Recount(tablets("20"), tablets("7"))))
    }
}
