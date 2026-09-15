package com.kert0n.medapp.storage.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Чтение обязательства из базы: незнакомое не превращается молча во что-то знакомое. */
@RunWith(AndroidJUnit4::class)
class ReminderRoomRepositoryTest {

    private lateinit var database: MedAppDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() = database.close()

    /**
     * Экран 29 и баннеры читают невыполненное потоком проекций: обещанное видно, сказанное и
     * отозванное — нет, а новое обещание доходит без перечитывания.
     */
    @Test
    // Настоящие потоки Room и настоящее время: `runTest` ждал бы виртуально.
    fun theScreenObservesWhatIsStillOwed() = kotlinx.coroutines.runBlocking {
        val repository = ReminderRoomRepository(database, database.reminders())
        val day = LocalDate.of(2027, 3, 10)
        val now = Instant.parse("2027-03-10T09:00:00Z")
        val banner = com.kert0n.medapp.domain.notification.Reminder(
            com.kert0n.medapp.domain.notification.NotificationKey.expiry(com.kert0n.medapp.fixture.PACK, com.kert0n.medapp.domain.pack.ExpiryDate(day), com.kert0n.medapp.domain.notification.NotificationKind.EXPIRY_TODAY),
            com.kert0n.medapp.domain.notification.NotificationTarget.PackageCard(com.kert0n.medapp.fixture.PACK), now
        )
        val said = com.kert0n.medapp.domain.notification.Reminder(
            com.kert0n.medapp.domain.notification.NotificationKey.digest(day),
            com.kert0n.medapp.domain.notification.NotificationTarget.DayPlan(day), now, state = com.kert0n.medapp.domain.notification.Reminder.State.SHOWN, shownAt = now
        )
        repository.saveAll(listOf(banner, said))

        assertEquals(listOf(banner.projection()), repository.observeAwaiting(NoticeDelivery.IN_APP_BANNER).first())
        assertEquals(emptyList<com.kert0n.medapp.domain.notification.PendingNotice>(), repository.observeAwaiting(NoticeDelivery.SYSTEM).first())

        val owed = repository.observeAwaiting(NoticeDelivery.SYSTEM)
        val collected = kotlinx.coroutines.CompletableDeferred<List<com.kert0n.medapp.domain.notification.PendingNotice>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            owed.collect { if (it.isNotEmpty()) collected.complete(it) }
        }
        try {
            val intake = kotlin.uuid.Uuid.random()
            repository.saveAll(listOf(com.kert0n.medapp.domain.notification.Reminder(
                com.kert0n.medapp.domain.notification.NotificationKey.intake(intake, com.kert0n.medapp.domain.notification.NotificationKind.INTAKE_DUE),
                com.kert0n.medapp.domain.notification.NotificationTarget.Intake(intake), now
            )))
            val arrived = kotlinx.coroutines.withTimeoutOrNull(5_000) { collected.await() }
            assertEquals(listOf(com.kert0n.medapp.domain.notification.NotificationTarget.Intake(intake)), arrived?.map { it.target })
        } finally {
            job.cancel()
        }
    }

    /**
     * Строка с незнакомым видом цели — испорченные данные или недописанный перенос, и читать её как
     * «сводку дня» нельзя: человек получил бы уведомление не о том. Чтение должно отказаться.
     */
    @Test
    fun anUnknownTargetKindIsRefusedInsteadOfBecomingTheDayPlan() = runTest {
        database.reminders().saveAll(
            listOf(ReminderStorageEntity(
                key = "DAILY_DIGEST:2027-03-10",
                kind = "DAILY_DIGEST",
                delivery = "SYSTEM",
                subject = "2027-03-10",
                targetKind = "MEDKIT",
                targetId = null,
                targetDate = LocalDate.of(2027, 3, 10),
                dueAt = Instant.parse("2027-03-10T09:00:00Z"),
                state = "DUE",
                shownAt = null,
                notBefore = null,
                attempts = 0
            ))
        )
        val repository = ReminderRoomRepository(database, database.reminders())

        val read = runCatching { repository.awaiting(NoticeDelivery.SYSTEM) }

        assertTrue("незнакомая цель прочиталась молча: ${read.getOrNull()}", read.isFailure)
    }
}
