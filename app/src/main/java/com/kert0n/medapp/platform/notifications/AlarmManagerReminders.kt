package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.ReminderAlarms
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Будильник к моменту приёма (PLAN D8): точный, если система разрешила `SCHEDULE_EXACT_ALARM`,
 * иначе приблизительный — и [canBeExact] честно говорит, какой. `USE_EXACT_ALARM` не берём.
 * Один ключ — один `PendingIntent` (код запроса — от ключа): повторная постановка переставляет,
 * `cancel` снимает тем же. В extras — только идентификатор пункта.
 */
@Singleton
class AlarmManagerReminders @Inject constructor(@ApplicationContext private val context: Context) : ReminderAlarms {

    private val manager get() = context.getSystemService(AlarmManager::class.java)

    /** До Android 12 разрешения на точные будильники нет — они точные всегда. */
    override val canBeExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    override suspend fun schedule(key: NotificationKey, at: Instant) {
        val intent = requireNotNull(pending(key, PendingIntent.FLAG_UPDATE_CURRENT)) { "с FLAG_UPDATE_CURRENT намерение есть всегда" }
        if (canBeExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    override suspend fun cancel(key: NotificationKey) {
        val intent = pending(key, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(intent)
        intent.cancel()
    }

    /** Стоит ли будильник по ключу — для проверок; сама система этого не рассказывает. */
    fun isScheduled(key: NotificationKey): Boolean = pending(key, PendingIntent.FLAG_NO_CREATE) != null

    private fun pending(key: NotificationKey, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, key.hashCode(), intentOf(key), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun intentOf(key: NotificationKey): Intent =
        Intent(context, IntakeAlarmReceiver::class.java)
            .setAction(IntakeAlarmReceiver.ACTION)
            .putExtra(IntakeAlarmReceiver.EXTRA_INTAKE_ID, key.subject)
}
