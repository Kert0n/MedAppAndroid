package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kert0n.medapp.feature.notification.ReminderOutbox
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Будильник сработал — и это всё, что приёмник знает (PLAN D8). Что наступило и что сказать,
 * решает владелец доставки, прочитав обязательства: в намерении нет ни ключей, ни идентификаторов.
 */
@AndroidEntryPoint
class ReminderWakeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var outbox: ReminderOutbox

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        outbox.runNow()
    }

    companion object {
        const val ACTION = "com.kert0n.medapp.REMINDERS_DUE"
    }
}
