package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Постановок **две** на всё приложение — точная и приблизительная (PLAN D8): каждая переставляется,
 * снимается и не зависит от того, сколько обязательств её ждут. Красная проверка прежнего
 * устройства: когда будильник был на каждый ключ, его тождество сводилось к `hashCode` — два
 * разных приёма могли поделить один `PendingIntent` и снять будильники друг друга; когда
 * постановка была одна, приблизительная на ближайший срок задерживала точный приём следом.
 */
@RunWith(AndroidJUnit4::class)
class AlarmManagerRemindersTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reminders = AlarmManagerReminders(context)

    @After
    fun tearDown() = runTest {
        reminders.stopWaking(exact = true)
        reminders.stopWaking(exact = false)
    }

    @Test
    fun eachExactnessIsSetReplacedAndCancelledOnItsOwn() = runTest {
        reminders.stopWaking(exact = true)
        reminders.stopWaking(exact = false)
        assertFalse(reminders.isScheduled(exact = true))
        assertFalse(reminders.isScheduled(exact = false))

        reminders.wakeAt(Instant.now().plusSeconds(3600), exact = true)
        assertTrue(reminders.isScheduled(exact = true))
        assertFalse("точная постановка не ставит приблизительную", reminders.isScheduled(exact = false))
        reminders.wakeAt(Instant.now().plusSeconds(7200), exact = false)
        assertTrue(reminders.isScheduled(exact = true))
        assertTrue(reminders.isScheduled(exact = false))

        reminders.stopWaking(exact = false)
        assertTrue("снятие приблизительной не трогает точную", reminders.isScheduled(exact = true))
        reminders.stopWaking(exact = true)
        assertFalse(reminders.isScheduled(exact = true))
    }

    /**
     * Сколько бы сроков ни ждало, запись у точности одна — и она отвечает последней постановке.
     * Именно поэтому коллизия хешей невозможна: различать нечего, кроме точности.
     */
    @Test
    fun manyMomentsShareTheSingleAlarmOfTheirExactness() = runTest {
        for (minutes in 1..5) reminders.wakeAt(Instant.now().plusSeconds(minutes * 60L), exact = true)

        val only = PendingIntent.getBroadcast(
            context, AlarmManagerReminders.REQUEST_EXACT,
            AlarmManagerReminders.wakeIntent(context, exact = true),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("точная постановка ровно одна и найдена своим кодом", only)
        assertFalse(reminders.isScheduled(exact = false))

        reminders.stopWaking(exact = true)
        assertFalse(reminders.isScheduled(exact = true))
    }

    @Test
    fun exactnessIsWhatTheSystemAllows() {
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        assertEquals(allowed, reminders.canBeExact)
    }

    /**
     * Постановка адресована **только** приёмнику пробуждения, своему действию и своей точности:
     * намерение с тем же адресом её находит, а с чужим действием или чужой точностью — нет. Что в
     * намерении нет идентификаторов, отсюда не видно: `PendingIntent` своего намерения не отдаёт.
     * Держит это `ReminderWakeReceiverTest`, который идёт настоящим широковещанием.
     */
    @Test
    fun theAlarmIsAddressedToTheWakeReceiverAndItsExactnessAlone() = runTest {
        reminders.wakeAt(Instant.now().plusSeconds(3600), exact = true)

        fun lookup(intent: Intent) = PendingIntent.getBroadcast(
            context, AlarmManagerReminders.REQUEST_EXACT, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        assertNotNull(lookup(AlarmManagerReminders.wakeIntent(context, exact = true)))
        assertEquals(null, lookup(AlarmManagerReminders.wakeIntent(context, exact = true).setAction("com.kert0n.medapp.SOMETHING_ELSE")))
        assertEquals(null, lookup(AlarmManagerReminders.wakeIntent(context, exact = false)))
    }
}
