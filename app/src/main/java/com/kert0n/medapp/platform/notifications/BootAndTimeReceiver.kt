package com.kert0n.medapp.platform.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.notification.ReminderOutbox
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Устройство загрузилось, перевели часы или сменили зону, вернули разрешение на точные будильники
 * — будильники в системе больше не те, что в базе (PLAN D8). Расписание сверяется проходом дня, а
 * постановки переставляет из таблицы их владелец: сверка без изменений ничего не пишет и сама
 * его не разбудит. Смена системной зоны зону курса не меняет: пункты стоят в своей зоне, и
 * будильники переставляются на те же моменты.
 */
@AndroidEntryPoint
class BootAndTimeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var daily: DailySchedule

    @Inject
    lateinit var outbox: ReminderOutbox

    override fun onReceive(context: Context, intent: Intent) {
        if (!restartsTheDay(intent.action)) return
        daily.runNow()
        outbox.runNow()
    }

    companion object {
        /** Какие сигналы системы требуют перестроить расписание; посторонние — нет. */
        fun restartsTheDay(action: String?): Boolean = action in TRIGGERS

        val TRIGGERS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
