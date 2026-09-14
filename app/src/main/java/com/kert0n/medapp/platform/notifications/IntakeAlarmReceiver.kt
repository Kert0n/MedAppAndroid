package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kert0n.medapp.feature.notification.NotificationDelivery
import com.kert0n.medapp.feature.notification.NotificationPlanning
import com.kert0n.medapp.queue.Synchronization
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Будильник сработал: перед показом — короткая сверка с сервером, чтобы остаток на уведомлении
 * был свежим, насколько успели; сервер молчит — показываем то, что знаем (PLAN D8, E4). Если пункт
 * всё ещё ждёт ответа — напоминание показано; отвеченный напоминания не получает: будильник мог
 * пережить ответ.
 */
@AndroidEntryPoint
class IntakeAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var planning: NotificationPlanning

    @Inject
    lateinit var delivery: NotificationDelivery

    @Inject
    lateinit var synchronization: Synchronization

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val intakeId = intent.getStringExtra(EXTRA_INTAKE_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                synchronization.refreshBriefly(REFRESH_WAIT)
                planning.reminderFor(intakeId)?.let { delivery.deliver(listOf(it)) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Дольше напоминание не ждёт: пара секунд — и показ с тем, что есть (PLAN D8). */
        val REFRESH_WAIT: Duration = Duration.ofSeconds(2)

        const val ACTION = "com.kert0n.medapp.INTAKE_DUE"
        const val EXTRA_INTAKE_ID = "intake_id"
    }
}
