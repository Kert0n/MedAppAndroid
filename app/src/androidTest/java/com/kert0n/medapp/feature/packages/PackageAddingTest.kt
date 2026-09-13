package com.kert0n.medapp.feature.packages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
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
 * Коробка заводится целым действием (PLAN D3, F5): запись, живая строка и детали — одной
 * транзакцией. На своей полке этим всё и кончается; полке, отвечающей серверу, коробку везёт
 * `Create`, а до ответа она помечена `CHANGING` и первое подтверждённое число приносит ответ (E1).
 */
@RunWith(AndroidJUnit4::class)
class PackageAddingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var adding: PackageAdding

    private val template: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000071")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        adding = Scenarios(database, LATER).packageAdding
    }

    @After
    fun tearDown() = database.close()

    private val facts = factsOf(pack(name = "Ибупрофен", form = TABLET_FORM, note = "от головы"))

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    @Test
    fun aBoxOnALocalShelfIsRecordedWholeAndSendsNothing() = runTest {
        val outcome = adding.add(HOME_KIT, facts, tablets("30"), templateId = template)

        val id = (outcome as PackageAdding.Outcome.Added).packageId
        val stored = requireNotNull(database.packageRepository().find(id))
        assertEquals("Ибупрофен", stored.name)
        assertEquals(tablets("30"), stored.quantity)
        assertEquals("от головы", stored.facts.note)
        assertEquals(template, stored.templateId)
        assertEquals(LATER, stored.addedAt)
        assertEquals(HOME_KIT, stored.medKit.id)
        assertEquals(PackageStatus.ACTIVE, stored.status)
        assertNull(stored.claims)
        // Запись о коробке заведена вместе с ней — с тем же именем и единицей.
        assertEquals("Ибупрофен", database.packages().find(id)?.record?.name)
        assertEquals(PackageSyncState(id), database.packageRepository().observeSyncState(id).first())
        assertTrue(commands().isEmpty())
    }

    /** Пустой коробки не бывает — это правило пачки, и сценарий его не повторяет. */
    @Test
    fun anEmptyBoxIsNotAdded() = runTest {
        val failure = runCatching { adding.add(HOME_KIT, facts, tablets("0")) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(database.packages().held().isEmpty())
    }

    @Test
    fun aBoxOnASharedShelfIsMarkedAndAnnouncedWithItsSharedFactsOnly() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )

        val id = (adding.add(SHARED_KIT, facts, tablets("30")) as PackageAdding.Outcome.Added).packageId

        val stored = requireNotNull(database.packageRepository().find(id))
        assertEquals(PackageStatus.CHANGING, stored.status)
        assertEquals("от головы", stored.facts.note)
        assertEquals(
            listOf(PackageSyncCommand.Create(id, SHARED_KIT)),
            commands()
        )
        // Помеченной `CHANGING` пользуются: расход из неё возможен.
        assertTrue(stored.take(com.kert0n.medapp.fixture.dose("1"), LATER).isSuccess)
    }

    /** Ответ сервера кладёт первое подтверждённое число с версией и снимает пометку (E1). */
    @Test
    fun theServerAnswerConfirmsTheBoxAndSettlesIt() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        val id = (adding.add(SHARED_KIT, facts, tablets("30")) as PackageAdding.Outcome.Added).packageId
        val stored = database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable

        val fromServer = pack(
            id = id,
            medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref,
            name = "Ибупрофен",
            quantity = tablets("30"),
            form = TABLET_FORM
        )
        database.queueStorage().settle(
            stored.operation.id,
            Delivery.Applied(PackageState.Present(PackageSnapshot(fromServer, PackageSyncState(id, ResourceVersion(1), null))))
                .settlement(stored.operation.command),
            LATER.plusSeconds(1)
        )

        val settled = requireNotNull(database.packageRepository().find(id))
        assertEquals(PackageStatus.ACTIVE, settled.status)
        assertEquals(tablets("30"), settled.quantity)
        // Личные сведения снимок не трогает.
        assertEquals("от головы", settled.facts.note)
        assertEquals(ResourceVersion(1), database.packageRepository().observeSyncState(id).first()?.version)
    }

    @Test
    fun aShelfBeingRemovedTakesNoBoxes() = runTest {
        database.medKits().upsert(medKit(id = HOME_KIT, status = MedKitStatus.REMOVING).toMedKitStorageEntity())

        assertEquals(PackageAdding.Outcome.MedKitBusy, adding.add(HOME_KIT, facts, tablets("30")))
        assertTrue(database.packages().held().isEmpty())
    }

    @Test
    fun aShelfThatIsGoneTakesNoBoxes() = runTest {
        assertEquals(PackageAdding.Outcome.MedKitGone, adding.add(Uuid.random(), facts, tablets("30")))
    }
}
