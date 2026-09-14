package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.fixture.INTAKE
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обязательство сказать человеку — сущность со своей жизнью (PLAN D8): срок переживает всё, что
 * забывает система, а состояние отвечает на то, чего не знал журнал показов, — что ещё должны
 * сказать и что показано без повода.
 */
class ReminderTest {

    private val at = Instant.parse("2027-03-10T06:00:00Z")
    private val key = NotificationKey.intake(INTAKE, NotificationKind.INTAKE_DUE)

    private fun reminder() = Reminder(key, NotificationTarget.Intake(INTAKE), at)

    @Test
    fun nothingIsDueBeforeItsMoment() {
        val reminder = reminder()

        assertFalse(reminder.isDue(at.minusMillis(1)))
        assertTrue(reminder.isDue(at))
        assertTrue(reminder.isDue(at.plusSeconds(3600)))
    }

    /** Сказали — обещание исполнено: второй раз то же обязательство не наступает. */
    @Test
    fun whatWasSaidIsNotDueAgain() {
        val reminder = reminder()

        reminder.deliveredAt(at)

        assertEquals(Reminder.State.SHOWN, reminder.state)
        assertEquals(at, reminder.shownAt)
        assertFalse(reminder.isDue(at.plusSeconds(3600)))
    }

    /**
     * Отложенное приходит снова — и это то же обязательство, а не новое. Красная проверка: не
     * возвращать состояние в `DUE` — отложенное молчало бы навсегда, потому что его уже показали.
     */
    @Test
    fun aDeferredReminderComesBackWithItsNewMoment() {
        val reminder = reminder()
        reminder.deliveredAt(at)
        val later = at.plusSeconds(15 * 60)

        reminder.defer(later)

        assertEquals(later, reminder.dueAt)
        assertEquals(Reminder.State.DUE, reminder.state)
        assertFalse(reminder.isDue(later.minusMillis(1)))
        assertTrue(reminder.isDue(later))
        // Момент первого показа — это журнал, и сдвиг его не двигает.
        assertEquals(at, reminder.shownAt)
    }

    /** Первый показ побеждает: повтор момента не двигает. */
    @Test
    fun theJournalKeepsTheFirstShowing() {
        val reminder = reminder()
        reminder.deliveredAt(at)
        reminder.defer(at.plusSeconds(60))

        reminder.deliveredAt(at.plusSeconds(60))

        assertEquals(at, reminder.shownAt)
    }

    /** Повода больше нет: отозванное не наступает и не откладывается. */
    @Test
    fun aWithdrawnReminderIsNeverDueAgain() {
        val reminder = reminder()

        reminder.withdraw()

        assertFalse(reminder.isDue(at.plusSeconds(3600)))
        assertTrue(runCatching { reminder.defer(at.plusSeconds(60)) }.isFailure)
    }

    /**
     * Тождество — ключ: то же обязательство с другим сроком остаётся тем же. На этом держится
     * сверка — она заводит недостающее и **не трогает** уже обещанное.
     */
    @Test
    fun identityIsTheKeyAndNotTheMoment() {
        val planned = reminder()
        val deferred = reminder().apply { defer(at.plusSeconds(900)) }
        val missed = Reminder(NotificationKey.intake(INTAKE, NotificationKind.INTAKE_MISSED), NotificationTarget.Intake(INTAKE), at)

        assertEquals(planned, deferred)
        assertEquals(planned.hashCode(), deferred.hashCode())
        assertNotEquals(planned, missed)
    }

    @Test
    fun aFreshReminderHasNotBeenSaidYet() {
        val reminder = reminder()

        assertEquals(Reminder.State.DUE, reminder.state)
        assertNull(reminder.shownAt)
    }
}
