package com.kert0n.medapp.storage.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
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
        storage = SnapshotRoomStorage(
            database, database.medKits(), database.packages(), database.courses(),
            database.stockMovements(), database.vocabulary()
        )
        database.medKits().upsert(
            medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 1).toMedKitStorageEntity()
        )
    }

    @After
    fun tearDown() = database.close()

    private fun snapshot(id: Uuid, medKitId: Uuid = HOME_KIT, quantity: String = "17") = PackageSnapshot(
        pack = pack(
            id = id,
            medKit = medKit(id = medKitId, publication = MedKit.Publication.PUBLISHED).ref,
            quantity = tablets(quantity)
        ),
        sync = PackageSyncState(id, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))
    )

    private fun serverSnapshot(
        participants: Map<Uuid, Long>,
        packages: List<PackageSnapshot>,
        goneMedKits: Set<Uuid> = emptySet(),
        gonePackages: Set<Uuid> = emptySet()
    ) = ServerSnapshot(participants, packages, goneMedKits, gonePackages)

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
    }

    /**
     * Сорвалась укладка одной коробки — не остаётся половины снимка: ни чужих коробок, ни нового
     * числа участников. Полки, которую называет вторая коробка, у нас нет, и ключ схемы это ловит.
     */
    @Test
    fun aSnapshotThatCouldNotBeLaidDownWholeLeavesNothing() = runTest {
        val refusal = runCatching {
            storage.lay(
                serverSnapshot(mapOf(HOME_KIT to 3L), listOf(snapshot(PACK), snapshot(OTHER_PACK, medKitId = Uuid.random()))),
                at
            )
        }.exceptionOrNull()

        assertNotNull(refusal)
        assertEquals(1L, database.medKits().find(HOME_KIT)?.toDomain()?.participantCount)
        assertNull(database.packageRepository().find(PACK))
    }
}
