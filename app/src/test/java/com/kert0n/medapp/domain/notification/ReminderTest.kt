package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.fixture.INTAKE
import java.time.Duration
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

    /**
     * Журнал помнит **последний** показ, а не первый. Прежде побеждал первый, и это уничтожало
     * живое обязательство: отложенное говорят снова, а срок хранения по-прежнему отсчитывался от
     * первого раза — строку забывали, пока человек ещё ждал напоминания.
     */
    @Test
    fun theJournalKeepsTheLastShowing() {
        val reminder = reminder()
        reminder.deliveredAt(at)
        val later = at.plusSeconds(60)
        reminder.defer(later)

        reminder.deliveredAt(later)

        assertEquals(later, reminder.shownAt)
    }

    /**
     * Сбой — не отказ: повод жив, и мы вернёмся, но не сейчас же. Задержка растёт с попытками,
     * иначе проход крутился бы вокруг несказанного без передышки.
     */
    @Test
    fun aFailureMovesTheMomentForwardAndGrowsWithAttempts() {
        val reminder = reminder()

        reminder.failedAt(at)
        val first = requireNotNull(reminder.wakeAt(at))
        assertTrue(first.isAfter(at))
        assertFalse(reminder.isDue(at))

        reminder.failedAt(first)
        val second = requireNotNull(reminder.wakeAt(first))
        assertTrue("вторая задержка должна быть длиннее первой", Duration.between(first, second) > Duration.between(at, first))
    }

    /**
     * Наступившее и несказанное будильника **не просит**: сказать ему мешает не время. Будильник на
     * прошедший момент система исполняет немедленно — и устройство будилось бы без конца.
     */
    @Test
    fun whatIsDueAndUnsaidAsksForNoAlarm() {
        val reminder = reminder()

        assertEquals(at, reminder.wakeAt(at.minusSeconds(1)))
        assertNull(reminder.wakeAt(at))
        assertNull(reminder.wakeAt(at.plusSeconds(3600)))
    }

    /** Отозванное и несказанное возвращается, когда повод вернулся; сказанное — уже нет. */
    @Test
    fun onlyAnUnshownWithdrawalCanBeRevived() {
        val unshown = reminder().apply { withdraw() }
        assertTrue(unshown.revivable())
        unshown.reviveAt(at.plusSeconds(120))
        assertEquals(Reminder.State.DUE, unshown.state)

        val said = reminder().apply { deliveredAt(at); withdraw() }
        assertFalse(said.revivable())
        assertTrue(runCatching { said.reviveAt(at) }.isFailure)
    }

    /** Давнее забывается; свежее — нет. Иначе таблица растёт всю жизнь установки. */
    @Test
    fun whatIsOldEnoughIsForgotten() {
        val longPast = at.plus(Reminder.RETENTION).plusSeconds(1)

        assertFalse(reminder().forgettable(at))
        assertTrue("наступившее, но так и не сказанное, тоже стареет", reminder().forgettable(longPast))
        assertTrue(reminder().apply { withdraw() }.forgettable(at))
        assertFalse(reminder().apply { deliveredAt(at) }.forgettable(at))
        assertTrue(reminder().apply { deliveredAt(at) }.forgettable(longPast))
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
