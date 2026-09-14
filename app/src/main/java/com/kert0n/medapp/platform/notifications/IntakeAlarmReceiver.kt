package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kert0n.medapp.feature.notification.NotificationDelivery
import com.kert0n.medapp.feature.notification.NotificationPlanning
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Будильник сработал: если пункт всё ещё ждёт ответа — напоминание показано (PLAN D8). Отвеченный
 * пункт напоминания не получает: будильник мог пережить ответ.
 */
@AndroidEntryPoint
class IntakeAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var planning: NotificationPlanning

    @Inject
    lateinit var delivery: NotificationDelivery

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val intakeId = intent.getStringExtra(EXTRA_INTAKE_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                planning.reminderFor(intakeId)?.let { delivery.deliver(listOf(it)) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.kert0n.medapp.INTAKE_DUE"
        const val EXTRA_INTAKE_ID = "intake_id"
    }
}
