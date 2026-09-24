package com.kert0n.medapp.feature.packages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.withShared
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
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
 * Сведения правятся целым действием (PLAN D3, F5). Личное остаётся на устройстве всегда; общее на
 * полке, отвечающей серверу, уезжает `Describe(before, after)` и помечает коробку; правка, не
 * вышедшая за границу публикации, команды не ставит.
 */
@RunWith(AndroidJUnit4::class)
class PackageDescribingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var describing: PackageDescribing

    private val sync = PackageSyncState(PACK, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        describing = Scenarios(database, LATER).packageDescribing
    }

    @After
    fun tearDown() = database.close()

    private suspend fun shared() {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, form = TABLET_FORM, note = "старая"),
            sync
        )
    }

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    @Test
    fun aLocalBoxIsDescribedWholeAndSendsNothing() = runTest {
        database.packageRepository().add(pack(quantity = tablets("20"), form = TABLET_FORM))
        val facts = factsOf(pack(form = TABLET_FORM)).withShared(name = "Панадол").copy(expiresOn = expiry("2027-03-31"), note = "в машине")

        assertEquals(PackageDescribing.Outcome.SAVED, describing.describe(PACK, facts))

        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals("Панадол", stored.name)
        assertEquals(expiry("2027-03-31"), stored.facts.expiresOn)
        assertEquals("в машине", stored.facts.note)
        // Остаток правка не трогает, и запись о коробке идёт за именем.
        assertEquals(tablets("20"), stored.quantity)
        assertEquals("Панадол", database.packages().find(PACK)?.record?.name)
        assertEquals(PackageStatus.ACTIVE, stored.status)
        assertTrue(commands().isEmpty())
    }

    /** Личная правка общей коробки — такая же местная запись: серверу о сроке и заметке нечего сказать. */
    @Test
    fun aPrivateChangeOnASharedBoxStaysLocal() = runTest {
        shared()
        val facts = factsOf(pack(form = TABLET_FORM)).copy(note = "новая", expiresOn = expiry("2027-03-31"))

        assertEquals(PackageDescribing.Outcome.SAVED, describing.describe(PACK, facts))

        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals("новая", stored.facts.note)
        assertEquals(PackageStatus.ACTIVE, stored.status)
        assertTrue(commands().isEmpty())
        // Обвязка синхронизации правке не принадлежит и остаётся прежней.
        assertEquals(sync, database.packageRepository().observeSyncState(PACK).first())
    }

    @Test
    fun aSharedChangeIsWrittenAnnouncedAndMarked() = runTest {
        shared()
        val before = factsOf(pack(form = TABLET_FORM)).shared
        val facts = factsOf(pack(form = TABLET_FORM)).withShared(name = "Панадол", category = "жар").copy(note = "новая")

        assertEquals(PackageDescribing.Outcome.SAVED, describing.describe(PACK, facts))

        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals("Панадол", stored.name)
        assertEquals("новая", stored.facts.note)
        assertEquals(PackageStatus.CHANGING, stored.status)
        assertEquals(listOf(PackageSyncCommand.Describe(PACK, before, facts.shared)), commands())
        assertEquals(sync, database.packageRepository().observeSyncState(PACK).first())
    }

    /** Форму серверной коробки очистить нечем: правка отвергается целиком, ничего не записано. */
    @Test
    fun clearingTheFormOfAServerBoxIsRefusedWhole() = runTest {
        shared()
        val facts = factsOf(pack(form = TABLET_FORM)).withShared(form = null).copy(note = "новая")

        assertEquals(PackageDescribing.Outcome.FORM_CLEAR_UNSUPPORTED, describing.describe(PACK, facts))

        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(TABLET_FORM, stored.facts.form)
        assertEquals("старая", stored.facts.note)
        assertTrue(commands().isEmpty())
    }

    @Test
    fun aBoxWaitingForItsRemovalIsNotDescribed() = runTest {
        shared()
        assertTrue(database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = Uuid.random()))

        val outcome = describing.describe(PACK, factsOf(pack(form = TABLET_FORM)).withShared(name = "Панадол"))

        assertEquals(PackageDescribing.Outcome.UNUSABLE, outcome)
        assertEquals("Парацетамол", database.packageRepository().find(PACK)?.name)
        assertTrue(commands().isEmpty())
    }

    @Test
    fun aBoxThatIsGoneSaysSo() = runTest {
        assertEquals(PackageDescribing.Outcome.GONE, describing.describe(Uuid.random(), factsOf(pack())))
        assertNull(database.packageRepository().find(PACK))
    }

    /**
     * Пока сервер о коробке не знает — её создание только уехало, — правка сведений остаётся у нас
     * и командой не едет: менять у сервера нечего, а нынешние сведения увезёт то же создание,
     * собранное по прочитанной коробке (PLAN E6, замечание разбора #24).
     */
    @Test
    fun anEditOfABoxTheServerDoesNotKnowYetStaysLocal() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM)
        )

        val outcome = describing.describe(PACK, factsOf(requireNotNull(database.packageRepository().find(PACK))).withShared(name = "Панадол"))

        assertEquals(PackageDescribing.Outcome.SAVED, outcome)
        val stored = requireNotNull(database.packageRepository().find(PACK))
        assertEquals("Панадол", stored.name)
        assertEquals(PackageStatus.ACTIVE, stored.status)
        assertTrue(commands().isEmpty())
    }
}
