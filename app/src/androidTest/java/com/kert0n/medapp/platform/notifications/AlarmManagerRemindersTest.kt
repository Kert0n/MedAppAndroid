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
 * Будильник **один** на всё приложение (PLAN D8): он переставляется, снимается и не зависит от
 * того, сколько обязательств его ждут. Красная проверка прежнего устройства: когда будильник был
 * на каждый ключ, его тождество сводилось к `hashCode` — два разных приёма могли поделить один
 * `PendingIntent` и снять будильники друг друга.
 */
@RunWith(AndroidJUnit4::class)
class AlarmManagerRemindersTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reminders = AlarmManagerReminders(context)

    @After
    fun tearDown() = runTest { reminders.stopWaking() }

    @Test
    fun oneAlarmIsSetReplacedAndCancelled() = runTest {
        reminders.stopWaking()
        assertFalse(reminders.isScheduled())

        reminders.wakeAt(Instant.now().plusSeconds(3600), exact = true)
        assertTrue(reminders.isScheduled())
        reminders.wakeAt(Instant.now().plusSeconds(7200), exact = false)
        assertTrue(reminders.isScheduled())

        reminders.stopWaking()
        assertFalse(reminders.isScheduled())
    }

    /**
     * Сколько бы сроков ни ждало, запись в системе одна — и она отвечает последней постановке.
     * Именно поэтому коллизия хешей больше невозможна: различать нечего.
     */
    @Test
    fun manyMomentsShareTheSingleAlarm() = runTest {
        for (minutes in 1..5) reminders.wakeAt(Instant.now().plusSeconds(minutes * 60L), exact = true)

        val only = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderWakeReceiver::class.java).setAction(ReminderWakeReceiver.ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("будильник ровно один и он найден своим единственным кодом", only)

        reminders.stopWaking()
        assertFalse(reminders.isScheduled())
    }

    @Test
    fun exactnessIsWhatTheSystemAllows() {
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        assertEquals(allowed, reminders.canBeExact)
    }

    /**
     * Будильник адресован **только** приёмнику пробуждения и своему действию: намерение с тем же
     * адресом его находит, а с чужим действием — нет. Что в намерении нет идентификаторов, отсюда
     * не видно: `PendingIntent` своего намерения не отдаёт, и проверять это пришлось бы намерением,
     * построенным в самой проверке, — то есть ничем. Держит это `ReminderWakeReceiverTest`, который
     * идёт настоящим широковещанием.
     */
    @Test
    fun theAlarmIsAddressedToTheWakeReceiverAlone() = runTest {
        reminders.wakeAt(Instant.now().plusSeconds(3600), exact = true)

        fun lookup(action: String) = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderWakeReceiver::class.java).setAction(action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        assertNotNull(lookup(ReminderWakeReceiver.ACTION))
        assertEquals(null, lookup("com.kert0n.medapp.SOMETHING_ELSE"))
    }
}
