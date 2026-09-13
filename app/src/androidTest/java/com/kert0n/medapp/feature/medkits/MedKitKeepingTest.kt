package com.kert0n.medapp.feature.medkits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.LocalDate
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
 * Полка заводится и правится целым действием (PLAN D2, F5). Имя и место хранения серверу
 * неизвестны (C0), поэтому ни одна из правок — ни у своей полки, ни у общей — команды не ставит,
 * а сама правка идёт названными полями к полке, какая она в базе сейчас.
 */
@RunWith(AndroidJUnit4::class)
class MedKitKeepingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var keeping: MedKitKeeping

    private val today: LocalDate = LocalDate.of(2026, 9, 10)

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        keeping = Scenarios(database, LATER).medKitKeeping
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun aNewShelfIsLocalWithOneParticipantAndNoCommands() = runTest {
        val created = keeping.create("Дача", location = "в кладовке")

        val stored = requireNotNull(database.medKitRepository().find(created.id))
        assertEquals("Дача", stored.name)
        assertEquals("в кладовке", stored.location)
        assertEquals(MedKit.Publication.LOCAL, stored.publication)
        assertEquals(1L, stored.participantCount)
        assertEquals(MedKitStatus.ACTIVE, stored.status)
        assertEquals(LATER, stored.createdAt)
        assertNull(database.medKits().find(created.id)?.syncedAt)
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** Допустимость названия — правило самой аптечки, и сценарий его не повторяет. */
    @Test
    fun aShelfWithoutANameIsNotCreated() = runTest {
        val before = database.medKitRepository().observeAll(today).first()
        assertTrue(runCatching { keeping.create("   ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, database.medKitRepository().observeAll(today).first())
    }

    /**
     * Правка общей полки — такая же местная запись, как правка своей: число участников, публикация
     * и момент сверки остаются теми, что положил снимок, а команда серверу не ставится.
     */
    @Test
    fun describingASharedShelfChangesOnlyTheNamedFieldsAndSendsNothing() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 3)
                .toMedKitStorageEntity(syncedAt = LATER)
        )

        assertEquals(MedKitKeeping.Outcome.SAVED, keeping.describe(SHARED_KIT, "Дача", location = null))

        val row = requireNotNull(database.medKits().find(SHARED_KIT))
        assertEquals("Дача", row.name)
        assertNull(row.location)
        assertEquals(3L, row.participantCount)
        assertEquals(LATER, row.syncedAt)
        assertEquals(MedKit.Publication.PUBLISHED, row.toDomain().publication)
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** Публикуемую полку правят: решение о ней правка не меняет, и пометка остаётся. */
    @Test
    fun describingAShelfBeingPublishedKeepsItsMark() = runTest {
        database.medKits().upsert(medKit(id = HOME_KIT, status = MedKitStatus.PUBLISHING).toMedKitStorageEntity())

        assertEquals(MedKitKeeping.Outcome.SAVED, keeping.describe(HOME_KIT, "Домашняя", location = "прихожая"))

        val stored = requireNotNull(database.medKitRepository().find(HOME_KIT))
        assertEquals("прихожая", stored.location)
        assertEquals(MedKitStatus.PUBLISHING, stored.status)
    }

    /** Полку, которую убирают, не правят: человек уже решил её судьбу (PLAN E1). */
    @Test
    fun aShelfBeingRemovedIsBusyAndStaysAsItWas() = runTest {
        database.medKits().upsert(medKit(id = HOME_KIT, status = MedKitStatus.REMOVING).toMedKitStorageEntity())

        assertEquals(MedKitKeeping.Outcome.BUSY, keeping.describe(HOME_KIT, "Другая", location = null))

        assertEquals("Домашняя", database.medKitRepository().find(HOME_KIT)?.name)
    }

    @Test
    fun describingAShelfThatIsGoneSaysSo() = runTest {
        assertEquals(MedKitKeeping.Outcome.GONE, keeping.describe(Uuid.random(), "Другая", location = null))
    }
}
