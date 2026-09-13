package com.kert0n.medapp.storage.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
