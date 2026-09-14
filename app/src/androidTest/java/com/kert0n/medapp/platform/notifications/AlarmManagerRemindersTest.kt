package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.fixture.INTAKE
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Будильник по ключу: стоит, переставляется, снимается; в extras — только идентификатор пункта (PLAN D8, G3). */
@RunWith(AndroidJUnit4::class)
class AlarmManagerRemindersTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reminders = AlarmManagerReminders(context)
    private val key = NotificationKey.intake(INTAKE, NotificationKind.INTAKE_DUE)

    @Test
    fun anAlarmIsSetReplacedAndCancelledByItsKey() = runTest {
        reminders.cancel(key)
        assertFalse(reminders.isScheduled(key))

        reminders.schedule(key, Instant.now().plusSeconds(3600))
        assertTrue(reminders.isScheduled(key))
        reminders.schedule(key, Instant.now().plusSeconds(7200))
        assertTrue(reminders.isScheduled(key))

        reminders.cancel(key)
        assertFalse(reminders.isScheduled(key))
    }

    @Test
    fun exactnessIsWhatTheSystemAllows() {
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        assertEquals(allowed, reminders.canBeExact)
    }

    @Test
    fun theIntentCarriesOnlyTheIntakeId() = runTest {
        reminders.schedule(key, Instant.now().plusSeconds(3600))
        val intent = Intent(context, IntakeAlarmReceiver::class.java).setAction(IntakeAlarmReceiver.ACTION).putExtra(IntakeAlarmReceiver.EXTRA_INTAKE_ID, key.subject)
        val pending = assertNotNull(PendingIntent.getBroadcast(context, key.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        assertNotNull(pending)
        assertEquals(setOf(IntakeAlarmReceiver.EXTRA_INTAKE_ID), intent.extras?.keySet())
        reminders.cancel(key)
    }
}
