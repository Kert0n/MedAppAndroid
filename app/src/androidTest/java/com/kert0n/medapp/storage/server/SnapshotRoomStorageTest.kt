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
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
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
 * Снимок утверждает о целом — «вот всё, что мне доступно», — поэтому и ложится целиком, одной
 * транзакцией: половина правды противоречила бы другой половине (PLAN E4, F5).
 */
@RunWith(AndroidJUnit4::class)
class SnapshotRoomStorageTest {

    private lateinit var database: MedAppDatabase
    private lateinit var storage: SnapshotRoomStorage

    private val at: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        storage = SnapshotRoomStorage(database, database.medKits(), database.packages())
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

    /** Участники полки и серверная часть её коробок ложатся вместе, одной записью. */
    @Test
    fun participantsAndPackagesGoDownTogether() = runTest {
        storage.lay(mapOf(HOME_KIT to 3L), listOf(snapshot(PACK), snapshot(OTHER_PACK)), at)

        assertEquals(3L, database.medKits().find(HOME_KIT)?.toDomain()?.participantCount)
        assertEquals(tablets("17"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(tablets("17"), database.packageRepository().find(OTHER_PACK)?.quantity)
        // Чужая коробка, увиденная впервые, датируется моментом наблюдения (PLAN E4, F1).
        assertEquals(at, database.packages().find(PACK)?.record?.addedAt)
    }

    /**
     * Сорвалась укладка одной коробки — не остаётся половины снимка: ни чужих коробок, ни нового
     * числа участников. Полки, которую называет вторая коробка, у нас нет, и ключ схемы это ловит.
     */
    @Test
    fun aSnapshotThatCouldNotBeLaidDownWholeLeavesNothing() = runTest {
        val refusal = runCatching {
            storage.lay(mapOf(HOME_KIT to 3L), listOf(snapshot(PACK), snapshot(OTHER_PACK, medKitId = Uuid.random())), at)
        }.exceptionOrNull()

        assertNotNull(refusal)
        assertEquals(1L, database.medKits().find(HOME_KIT)?.toDomain()?.participantCount)
        assertNull(database.packageRepository().find(PACK))
    }
}
