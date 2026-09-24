package com.kert0n.medapp.feature.medkits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.operation.toStorageEntity as toIntakeStorageEntity
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Человек убирает общую полку, оставляя её остальным, — выходит (PLAN E6): коробки целы, но не у нас — утрата доступа в
 * историю, курс теряет источники с этой полки и только их, история лечения остаётся, серверу —
 * `Leave`.
 */
@RunWith(AndroidJUnit4::class)
class MedKitLeaveToOthersTest {

    private lateinit var database: MedAppDatabase
    private lateinit var removal: MedKitRemoval

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        removal = Scenarios(database, LATER).medKitRemoval
        database.medKits().upsert(
            medKit(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participantCount = 2)
                .toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(id = PACK, medKit = medKit(id = SHARED_KIT).ref, quantity = tablets("7"), form = TABLET_FORM)
        )
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("20"), form = TABLET_FORM))
        val plan = activeCourse(sources = listOf(source(PACK, 3), source(OTHER_PACK, 5)))
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
        database.intakes().upsert(
            plannedIntake().confirm(pack(id = PACK).take(dose("2"), LATER).getOrThrow()).toIntakeStorageEntity()
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    /**
     * Выход — наше участие, но узнать о нём мы можем только от сервера: до его ответа полка на
     * месте, коробки целы, курс держит источники (PLAN E1, E3, E6).
     */
    @Test
    fun leavingMarksTheShelfAndWaitsForTheServer() = runTest {
        val outcome = removal.remove(SHARED_KIT, MedKitRemoval.Fate.LeaveToOthers)

        assertEquals(MedKitRemoval.Outcome.MARKED, outcome)
        assertNotNull(database.medKits().find(SHARED_KIT))
        assertNotNull(database.packageRepository().find(PACK))
        assertEquals(listOf(PACK, OTHER_PACK).sorted(), database.courses().sourcePackagesOf(COURSE).sorted())
        assertEquals(listOf(MedKitSyncCommand.Leave(SHARED_KIT)), commands())
        // Коробки полки видны, но уже не наши; коробка с другой полки не тронута.
        assertEquals(MedKitStatus.REMOVING, database.medKits().find(SHARED_KIT)?.toDomain()?.status)
        assertEquals(PackageStatus.LOST, database.packageRepository().find(PACK)?.status)
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(OTHER_PACK)?.status)
        assertEquals(MedKitRemoval.Outcome.BUSY, removal.remove(SHARED_KIT, MedKitRemoval.Fate.LeaveToOthers))
    }

    /**
     * Сервер согласился: коробки целы, но не у нас — их строк больше нет, курс теряет источники
     * **с этой полки и только их**, а лечение и его история остаются (PLAN D5, E6).
     */
    @Test
    fun theServerAgreeingLosesTheShelfsPackagesAndKeepsTheCourse() = runTest {
        removal.remove(SHARED_KIT, MedKitRemoval.Fate.LeaveToOthers)

        theServerAgrees()

        assertNull(database.medKits().find(SHARED_KIT))
        assertNull(database.packageRepository().find(PACK))
        assertNotNull(database.packageRepository().find(OTHER_PACK))
        assertEquals(listOf(OTHER_PACK), database.courses().sourcePackagesOf(COURSE))
        assertNotNull(database.courses().findRecord(COURSE))
        assertEquals("Парацетамол", requireNotNull(database.intakes().find(INTAKE)).toDomain(VOCABULARY).taken?.pkg?.name)
    }

    /**
     * Сервер согласился. Исход доводит до конца очередь — тем же путём, что и в бою: эффект
     * закрытия операции, а не отдельная дверь для теста.
     */
    private suspend fun theServerAgrees() {
        val stored = database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        database.queueStorage().settle(
            stored.operation.id,
            Delivery.Applied(PackageState.None).settlement(stored.operation.command),
            LATER
        )
    }

    @Test
    fun aLocalMedKitIsNotLeft() = runTest {
        assertEquals(MedKitRemoval.Outcome.NOT_SHARED, removal.remove(HOME_KIT, MedKitRemoval.Fate.LeaveToOthers))
        assertNotNull(database.medKits().find(HOME_KIT))
        assertEquals(MedKitRemoval.Outcome.MED_KIT_GONE, removal.remove(Uuid.random(), MedKitRemoval.Fate.LeaveToOthers))
    }

    /**
     * Коробку унесли домой до выхода — она уже на местной полке, и выход её не касается: курс
     * держит оба источника, теряется только то, что осталось на покинутой полке (PLAN C1 «Курсы
     * при выходе», E6).
     */
    @Test
    fun aBoxCarriedHomeBeforeLeavingStaysASource() = runTest {
        val scenarios = Scenarios(database, LATER)
        val third = Uuid.random()
        database.packageRepository().add(pack(id = third, name = "Оставшаяся", medKit = medKit(id = SHARED_KIT).ref, quantity = tablets("9"), form = TABLET_FORM))
        val plan = requireNotNull(database.courseRepository().findPlan(COURSE))
        scenarios.sourceEditing.save(
            COURSE, plan.revision,
            listOf(SourceEditing.Source(PACK, Doses(3)), SourceEditing.Source(OTHER_PACK, Doses(5)), SourceEditing.Source(third, Doses(1)))
        )
        assertEquals(PackageRelocation.Outcome.MOVED, scenarios.packageRelocation.move(PACK, HOME_KIT))
        // Унос — тоже команда; сервер согласился, коробка дома и обычная.
        settleAll()

        assertEquals(MedKitRemoval.Outcome.MARKED, removal.remove(SHARED_KIT, MedKitRemoval.Fate.LeaveToOthers))
        settleAll()

        assertNull(database.medKits().find(SHARED_KIT))
        assertNotNull(database.packageRepository().find(PACK))
        assertNull(database.packageRepository().find(third))
        assertEquals(listOf(PACK, OTHER_PACK).sorted(), database.courses().sourcePackagesOf(COURSE).sorted())
        assertNotNull(database.courses().findRecord(COURSE))
    }

    /** Сервер согласился со всем, что ждёт, — тем же путём, что и в бою: эффектами закрытия. */
    private suspend fun settleAll() {
        for (row in database.syncOperations().all()) {
            val stored = row.toDomain(VOCABULARY) as StoredSyncOperation.Readable
            if (stored.operation.status.isClosed) continue
            database.queueStorage().settle(stored.operation.id, Delivery.Applied(PackageState.None).settlement(stored.operation.command), LATER)
        }
    }
}
