package com.kert0n.medapp.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.platform.notifications.AlarmManagerReminders
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Будильник сработал — и это всё, что приёмник знает (PLAN D8). Что наступило и что сказать,
 * решает владелец доставки, прочитав обязательства: в намерении нет ни ключей, ни идентификаторов.
 *
 * Приёмник **держит процесс** через `goAsync()` до конца прохода. Вернувшись раньше, он оставил бы
 * приложение без единого живого компонента: система вправе убить его тут же, и напоминание, ради
 * которого она нас разбудила, не состоялось бы.
 */
@AndroidEntryPoint
class ReminderWakeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var outbox: ReminderOutbox

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManagerReminders.ACTION) return
        passHoldingTheProcess(outbox, "проход по будильнику не удался")
    }
}
