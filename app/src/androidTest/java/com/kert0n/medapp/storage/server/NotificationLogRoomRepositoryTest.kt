package com.kert0n.medapp.storage.server

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Журнал показов различает вид, предмет и способ доставки; первый показ побеждает (PLAN D8). */
class NotificationLogRoomRepositoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var log: NotificationLogRoomRepository
    private val at: Instant = Instant.parse("2027-03-28T09:00:00Z")
    private val expiry = ExpiryDate(LocalDate.of(2027, 3, 31))

    @Before
    fun openDatabase() {
        database = inMemoryDatabase()
        log = NotificationLogRoomRepository(database.notificationLog())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun stagesAndDeliveriesOfOneEventAreDifferentShowings() = runTest {
        val threeDays = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_SOURCE_3D)
        val today = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_TODAY)

        log.remember(threeDays, NoticeDelivery.SYSTEM, at)

        assertTrue(log.wasShown(threeDays, NoticeDelivery.SYSTEM))
        assertFalse(log.wasShown(threeDays, NoticeDelivery.IN_APP_BANNER))
        assertFalse(log.wasShown(today, NoticeDelivery.SYSTEM))
        // Тот же предмет другим видом — другая строка: виды не склеиваются в один ключ.
        log.remember(today, NoticeDelivery.IN_APP_BANNER, at)
        assertEquals(2, database.notificationLog().ofKind(NotificationKind.EXPIRY_SOURCE_3D.name).size + database.notificationLog().ofKind(NotificationKind.EXPIRY_TODAY.name).size)
    }

    @Test
    fun theFirstShowingWinsAndForgettingClearsEveryDelivery() = runTest {
        val key = NotificationKey.intake(PACK, NotificationKind.INTAKE_DUE)
        log.remember(key, NoticeDelivery.SYSTEM, at)
        log.remember(key, NoticeDelivery.SYSTEM, at.plusSeconds(600))
        assertEquals(at, database.notificationLog().ofKind(NotificationKind.INTAKE_DUE.name).single().shownAt)

        log.forget(key)

        assertFalse(log.wasShown(key, NoticeDelivery.SYSTEM))
    }
}
