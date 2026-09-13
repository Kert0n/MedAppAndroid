package com.kert0n.medapp.storage.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Хранение аптечки: пометка ставится её же переходом, обвязка синхронизации живёт отдельно от
 * доменных полей, а снимок сервера трогает только число участников (PLAN E4, F1).
 */
class MedKitRoomRepositoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var medKits: MedKitRoomRepository

    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        medKits = database.medKitRepository()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /**
     * Момент сверки — обвязка доставки, а не доменное поле аптечки: у него свой метод для экрана
     * состояния синхронизации (PLAN E4, H3 №28).
     */
    @Test
    fun theMomentOfTheLastSyncIsObservedByItsOwnMethod() = runTest {
        assertNull(medKits.observeSyncedAt(HOME_KIT).first())

        medKits.applyServerParticipants(HOME_KIT, participantCount = 2, syncedAt = at)

        assertEquals(at, medKits.observeSyncedAt(HOME_KIT).first())
    }

    /**
     * Пометку ставит переход самой аптечки, прочитанной здесь же: решение о полке, о которой уже
     * принято другое, не записывается (PLAN E1, E5).
     */
    @Test
    fun aMarkComesFromTheKitsOwnTransition() = runTest {
        assertEquals(true, medKits.mark(HOME_KIT, MedKitStatus.PUBLISHING))
        assertEquals(MedKitStatus.PUBLISHING, requireNotNull(medKits.find(HOME_KIT)).status)
        assertEquals(MedKit.Publication.LOCAL, requireNotNull(medKits.find(HOME_KIT)).publication)

        assertNotNull(runCatching { medKits.mark(HOME_KIT, MedKitStatus.REMOVING) }.exceptionOrNull())
    }

    /** Снимать пометку — дело ответа сервера, и решением это не делается. */
    @Test
    fun aMarkIsNotLiftedByADecision() = runTest {
        assertNotNull(runCatching { medKits.mark(HOME_KIT, MedKitStatus.ACTIVE) }.exceptionOrNull())
    }
}
