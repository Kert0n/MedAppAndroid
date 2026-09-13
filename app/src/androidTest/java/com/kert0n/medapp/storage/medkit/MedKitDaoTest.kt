package com.kert0n.medapp.storage.medkit

import android.database.sqlite.SQLiteConstraintException
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.rejectedByDatabase
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity
import org.junit.Assert.assertTrue
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Снимок трогает у аптечки только число участников: название и место хранения серверу
 * неизвестны, и переписать их ему нечем (PLAN F1, E4).
 */
class MedKitDaoTest {

    private lateinit var database: MedAppDatabase
    private val medKits get() = database.medKits()

    @Before
    fun openDatabase() {
        database = inMemoryDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun serverParticipantsDoNotTouchNameAndLocation() = runTest {
        val published = medKit(
            id = SHARED_KIT,
            name = "Дача",
            location = "на веранде",
            publication = MedKit.Publication.PUBLISHED,
            participantCount = 1
        )
        medKits.upsert(published.toStorageEntity())

        val syncedAt = Instant.parse("2026-09-10T12:00:00Z")
        medKits.applyServerParticipants(SHARED_KIT, participantCount = 3, syncedAt = syncedAt)

        val stored = requireNotNull(medKits.find(SHARED_KIT))
        assertEquals("Дача", stored.name)
        assertEquals("на веранде", stored.location)
        assertEquals(3L, stored.participantCount)
        assertEquals(syncedAt, stored.syncedAt)
        assertEquals(published.createdAt, stored.createdAt)
    }

    @Test
    fun renamingKeepsPublicationAndParticipants() = runTest {
        val shared = medKit(
            id = SHARED_KIT,
            publication = MedKit.Publication.PUBLISHED,
            participantCount = 2
        )
        medKits.upsert(shared.toStorageEntity())

        val renamed = requireNotNull(medKits.find(SHARED_KIT)).toDomain().describe("Общая", "полка")
        medKits.upsert(renamed.toStorageEntity())

        val stored = requireNotNull(medKits.find(SHARED_KIT)).toDomain()
        assertEquals("Общая", stored.name)
        assertEquals("полка", stored.location)
        assertEquals(MedKit.Publication.PUBLISHED, stored.publication)
        assertEquals(2L, stored.participantCount)
    }

    /** Пачка не живёт без аптечки: аптечку с пачками база удалить не даёт (PLAN F2). */
    @Test
    fun aMedKitWithPackagesCannotBeDeleted() = runTest {
        val pkg = pack(medKit = medKit(id = HOME_KIT).ref)
        database.packages().save(pkg, PackageSyncState(pkg.id))

        val refusal = rejectedByDatabase { medKits.delete(HOME_KIT) }

        assertTrue(refusal.toString(), refusal is SQLiteConstraintException)
        assertEquals(HOME_KIT, requireNotNull(medKits.find(HOME_KIT)).id)
    }
}
