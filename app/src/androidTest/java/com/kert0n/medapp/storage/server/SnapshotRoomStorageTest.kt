package com.kert0n.medapp.storage.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import java.math.BigDecimal
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.stock.StockMovement
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимок утверждает о целом — «вот всё, что мне доступно», — поэтому и ложится целиком, одной
 * транзакцией: половина правды противоречила бы другой половине (PLAN E4, F5).
 */
@RunWith(AndroidJUnit4::class)
class SnapshotRoomStorageTest {

    private lateinit var database: MedAppDatabase
    private lateinit var storage: SnapshotRoomStorage

    private val at: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val third: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000077")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        storage = database.snapshotStorage()
        database.medKits().upsert(
            medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 1).toMedKitStorageEntity()
        )
    }

    @After
    fun tearDown() = database.close()

    private fun snapshot(id: Uuid, medKitId: Uuid = HOME_KIT, quantity: String = "17", version: Long = 4) = PackageSnapshot(
        pack = pack(
            id = id,
            medKit = medKit(id = medKitId, publication = MedKit.Publication.PUBLISHED).ref,
            quantity = tablets(quantity)
        ),
        sync = PackageSyncState(id, version = ResourceVersion(version), claimsVersion = ResourceVersion(2))
    )

    private suspend fun remoteChanges(id: Uuid = PACK): List<BigDecimal> {
        val words = database.vocabulary().snapshot()
        return database.stockMovements().ofPackage(id).map { it.toDomain(words) }
            .filterIsInstance<StockMovement.RemoteChange>().map { it.delta.stripTrailingZeros() }
    }

    private fun serverSnapshot(
        participants: Map<Uuid, Long>,
        packages: List<PackageSnapshot>,
        goneMedKits: Set<Uuid> = emptySet(),
        gonePackages: Set<Uuid> = emptySet(),
        arrivedMedKits: Set<Uuid> = emptySet(),
        heldPackages: Set<Uuid> = emptySet()
    ) = ServerSnapshot(participants, packages, goneMedKits, gonePackages, arrivedMedKits, heldPackages)

    /** Участники полки и серверная часть её коробок ложатся вместе, одной записью. */
    @Test
    fun participantsAndPackagesGoDownTogether() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 3L), listOf(snapshot(PACK), snapshot(OTHER_PACK))), at)

        assertEquals(3L, database.medKits().find(HOME_KIT)?.toDomain()?.participantCount)
        assertEquals(tablets("17"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(tablets("17"), database.packageRepository().find(OTHER_PACK)?.quantity)
        // Чужая коробка, увиденная впервые, датируется моментом наблюдения (PLAN E4, F1).
        assertEquals(at, database.packages().find(PACK)?.record?.addedAt)
    }

    /**
     * Обычный путь: сервер знает то же, что и мы. Снимок ложится целиком, и в истории нет ни одной
     * записи — ни появления, ни разницы (PLAN E1, E4).
     */
    @Test
    fun aSnapshotThatAgreesWithUsLeavesNoTrace() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK))), at)

        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK, version = 5))), at.plusSeconds(60))

        assertEquals(tablets("17"), database.packageRepository().find(PACK)?.quantity)
        assertTrue(database.stockMovements().ofPackage(PACK).isEmpty())
    }

    /**
     * Сосед принял таблетки — состав полки и число участников те же, а остаток другой: это видно
     * только полным снимком, и разница идёт в историю чужим изменением. Причину сервер не знает,
     * и она не выдумывается; повтор того же снимка второй записи не заводит (PLAN B6, D7).
     */
    @Test
    fun aNeighboursChangeIsWrittenOnceAsUnexplained() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK))), at)

        val changed = serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK, quantity = "12", version = 5)))
        storage.lay(changed, at.plusSeconds(60))
        storage.lay(changed, at.plusSeconds(120))

        assertEquals(tablets("12"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(listOf(BigDecimal("-5")), remoteChanges())
    }

    /**
     * Запрос по коробке уже ушёл, а ответ не лёг: снимок мог увидеть наш расход, а подтверждённое
     * число о нём не знает. Снимок коробку не трогает — иначе расход вычелся бы из числа дважды, а
     * наш же расход записался бы чужим. Истину принесёт ответ на ту же команду (PLAN E1, D7).
     */
    @Test
    fun aBoxWithARequestInFlightIsLeftToItsAnswer() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK))), at)
        val operation = Uuid.random()
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        database.queueStorage().take(operation, null, at)

        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK, quantity = "14", version = 5))), at)

        assertEquals(tablets("17"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(emptyList<BigDecimal>(), remoteChanges())
    }

    /**
     * Коробки, которую сервер не назвал, у нас больше нет: она уходит утратой доступа — со следом в
     * истории и через ту же дверь, что и всякий конец коробки (PLAN D7, E4).
     */
    @Test
    fun aBoxTheSnapshotDoesNotNameEndsWithAccessLost() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK))), at)

        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), emptyList(), gonePackages = setOf(PACK)), at)

        assertNull(database.packageRepository().find(PACK))
        // Живой строки нет, а след утраты есть: он держится за вечную запись о коробке (PLAN D3).
        val words = database.vocabulary().snapshot()
        assertTrue(database.stockMovements().ofPackage(PACK).any { it.toDomain(words) is StockMovement.AccessLoss })
    }

    /** Полки, которую сервер не назвал, у нас нет — и её содержимого тоже: они не бывают порознь. */
    @Test
    fun aShelfTheSnapshotDoesNotNameGoesWithItsContents() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK), snapshot(OTHER_PACK))), at)

        storage.lay(serverSnapshot(emptyMap(), emptyList(), goneMedKits = setOf(HOME_KIT)), at)

        assertNull(database.medKits().find(HOME_KIT))
        assertNull(database.packageRepository().find(PACK))
        assertNull(database.packageRepository().find(OTHER_PACK))
    }

    /**
     * О чём сервер знает: коробка без его версии ему неизвестна, а помеченная ждёт ответа на своё
     * решение — её отсутствие в снимке объяснит он, а не снимок (PLAN E4).
     */
    @Test
    fun whatTheServerKnowsLeavesOutTheUnsentAndTheUndecided() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK), snapshot(OTHER_PACK))), at)
        database.packages().setStatus(OTHER_PACK, PackageStatus.REMOVING)
        database.packageRepository().add(pack(id = third, quantity = tablets("5")))

        val knew = storage.serverKnows()

        assertEquals(setOf(HOME_KIT), knew.medKits)
        assertEquals(setOf(PACK), knew.packages)
        // Что у нас вообще есть — вопрос другой: сюда входит всё, и неотправленное, и помеченное.
        assertEquals(setOf(HOME_KIT, SHARED_KIT), knew.heldMedKits)
        assertEquals(setOf(PACK, OTHER_PACK, third), knew.heldPackages)
    }

    /**
     * Полка, которой у нас не было, приходит со снимком: имени сервер не знает, и она заводится
     * «Общей аптечкой», которую человек переименует сам, — вместе со своими коробками (PLAN C0, E4).
     */
    @Test
    fun aShelfTheServerNamesArrivesAsTheSharedMedKit() = runTest {
        val arrived = Uuid.random()

        storage.lay(
            serverSnapshot(mapOf(arrived to 3L), listOf(snapshot(PACK, medKitId = arrived)), arrivedMedKits = setOf(arrived)),
            at
        )

        val shelf = requireNotNull(database.medKits().find(arrived)).toDomain()
        assertEquals("Общая аптечка", shelf.name)
        assertEquals(MedKit.Publication.PUBLISHED, shelf.publication)
        assertEquals(3L, shelf.participantCount)
        assertTrue(shelf.acceptsInvitations)
        assertEquals(arrived, database.packageRepository().find(PACK)?.medKit?.id)
    }

    /** Полку уже завело вступление, пока снимок летел: имя, данное человеком, снимок не трогает. */
    @Test
    fun aShelfThatArrivedMeanwhileKeepsItsName() = runTest {
        storage.lay(serverSnapshot(mapOf(HOME_KIT to 4L), emptyList(), arrivedMedKits = setOf(HOME_KIT)), at)

        val shelf = requireNotNull(database.medKits().find(HOME_KIT)).toDomain()
        assertEquals("Домашняя", shelf.name)
        assertEquals(4L, shelf.participantCount)
    }

    /**
     * Коробку выбросили, пока снимок летел: к началу чтения она была, к укладке её нет. Ответ,
     * прочитанный раньше, об этом не знает — и не возвращает её (PLAN C0).
     */
    @Test
    fun aBoxRemovedWhileTheSnapshotFlewDoesNotComeBack() = runTest {
        storage.lay(
            serverSnapshot(mapOf(HOME_KIT to 2L), listOf(snapshot(PACK)), heldPackages = setOf(PACK)),
            at
        )

        assertNull(database.packages().find(PACK))
    }

    /** Полку убрали, пока снимок летел: её коробкам некуда лечь, и снимок их не заводит. */
    @Test
    fun boxesOfAShelfRemovedWhileTheSnapshotFlewAreNotLaid() = runTest {
        val removed = Uuid.random()

        storage.lay(serverSnapshot(mapOf(removed to 2L), listOf(snapshot(PACK, medKitId = removed))), at)

        assertNull(database.medKits().find(removed))
        assertNull(database.packages().find(PACK))
    }

    /**
     * Сорвалась укладка одной коробки — не остаётся половины снимка: ни чужих коробок, ни нового
     * числа участников. Единицы, которой считают вторую коробку, в словаре нет, и ключ схемы это
     * ловит.
     */
    @Test
    fun aSnapshotThatCouldNotBeLaidDownWholeLeavesNothing() = runTest {
        val unknownUnit = QuantityUnit(Uuid.random(), "неведомая")
        val broken = PackageSnapshot(
            pack = pack(
                id = OTHER_PACK,
                medKit = medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED).ref,
                quantity = Quantity(BigDecimal("5"), unknownUnit)
            ),
            sync = PackageSyncState(OTHER_PACK, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))
        )

        val refusal = runCatching {
            storage.lay(serverSnapshot(mapOf(HOME_KIT to 3L), listOf(snapshot(PACK), broken)), at)
        }.exceptionOrNull()

        assertNotNull(refusal)
        assertEquals(1L, database.medKits().find(HOME_KIT)?.toDomain()?.participantCount)
        assertNull(database.packageRepository().find(PACK))
    }
}
