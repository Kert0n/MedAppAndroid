package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kert0n.medapp.domain.notification.ReminderAlarms
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Один будильник на всё приложение (PLAN D8): он будит процесс к ближайшему невыполненному
 * обязательству, а что сказать — решает владелец доставки, прочитав таблицу. Поэтому намерение
 * ровно одно, и тождество `PendingIntent` не зависит от хеша ключа: два приёма одного мига не
 * делят одну запись и не снимают будильники друг друга.
 *
 * Точный — при разрешении `SCHEDULE_EXACT_ALARM`, иначе приблизительный; `USE_EXACT_ALARM` не
 * берём. В намерении нет ничего: ни идентификаторов, ни текстов (G3).
 */
@Singleton
class AlarmManagerReminders @Inject constructor(@ApplicationContext private val context: Context) : ReminderAlarms {

    private val manager get() = context.getSystemService(AlarmManager::class.java)

    /** До Android 12 разрешения на точные будильники нет — они точные всегда. */
    override val canBeExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    override suspend fun wakeAt(at: Instant, exact: Boolean) {
        val intent = requireNotNull(pending(PendingIntent.FLAG_UPDATE_CURRENT)) { "с FLAG_UPDATE_CURRENT намерение есть всегда" }
        if (exact && canBeExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    override suspend fun stopWaking() {
        val intent = pending(PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(intent)
        intent.cancel()
    }

    /** Стоит ли будильник — для проверок; сама система этого не рассказывает. */
    fun isScheduled(): Boolean = pending(PendingIntent.FLAG_NO_CREATE) != null

    private fun pending(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST, Intent(context, ReminderWakeReceiver::class.java).setAction(ReminderWakeReceiver.ACTION), flags or PendingIntent.FLAG_IMMUTABLE)

    private companion object {
        /** Будильник один — и код запроса у него один. */
        const val REQUEST = 0
    }
}
