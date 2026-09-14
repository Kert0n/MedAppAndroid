package com.kert0n.medapp.storage.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
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
