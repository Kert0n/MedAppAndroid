package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.platform.AppEntries
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Две постановки на всё приложение — точная и приблизительная (PLAN D8): каждая будит процесс к
 * ближайшему обязательству своей точности, а что сказать — решает владелец доставки, прочитав
 * таблицу. Тождество постановки — её точность: код запроса и `setIdentifier` намерения, — а не
 * хеш ключа, и два приёма одного мига не делят одну запись.
 *
 * Точная — `setExactAndAllowWhileIdle` при разрешении `SCHEDULE_EXACT_ALARM`, иначе она ставится
 * приблизительно, но остаётся **своей** постановкой; `USE_EXACT_ALARM` не берём. В намерении нет
 * ничего: ни идентификаторов, ни текстов (G3).
 */
@Singleton
class AlarmManagerReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entries: AppEntries
) : ReminderAlarms {

    private val manager get() = context.getSystemService(AlarmManager::class.java)

    /** До Android 12 разрешения на точные будильники нет — они точные всегда. */
    override val canBeExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    override suspend fun wakeAt(at: Instant, exact: Boolean) {
        val intent = requireNotNull(pending(exact, PendingIntent.FLAG_UPDATE_CURRENT)) { "с FLAG_UPDATE_CURRENT намерение есть всегда" }
        if (exact && canBeExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    override suspend fun stopWaking(exact: Boolean) {
        val intent = pending(exact, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(intent)
        intent.cancel()
    }

    /** Стоит ли постановка этой точности — для проверок; сама система этого не рассказывает. */
    fun isScheduled(exact: Boolean): Boolean = pending(exact, PendingIntent.FLAG_NO_CREATE) != null

    private fun pending(exact: Boolean, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, if (exact) REQUEST_EXACT else REQUEST_INEXACT, wakeIntent(context, entries, exact), flags or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** Постановок две — и кода запроса два: по одному на точность. */
        const val REQUEST_INEXACT = 0
        const val REQUEST_EXACT = 1

        /** Действие будильника; вход, которому он едет, другого не принимает. */
        const val ACTION = "com.kert0n.medapp.REMINDERS_DUE"

        /** Намерение постановки: приёмник, действие и точность в тождестве — и ничего больше. */
        fun wakeIntent(context: Context, entries: AppEntries, exact: Boolean): Intent =
            Intent(context, entries.reminderWake)
                .setAction(ACTION)
                .setIdentifier(if (exact) "exact" else "inexact")
    }
}
