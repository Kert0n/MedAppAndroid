package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.feature.notification.ReminderAnswering
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Действие с уведомления о приёме (PLAN D8): «Принял», «Пропустить», «Отложить» — сценарием
 * [ReminderAnswering], тем же, что и экран. Вопрос или отказ открывают приложение с одним
 * `intakeId` — предупреждение действием из шторки не обходится.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var answering: ReminderAnswering

    @Inject
    lateinit var notifier: Notifier

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.let { name -> NotificationAction.entries.firstOrNull { it.name == name } } ?: return
        val intakeId = intent.getStringExtra(EXTRA_INTAKE_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val response = when (action) {
                    NotificationAction.TAKE -> answering.take(intakeId)
                    NotificationAction.SKIP -> answering.skip(intakeId)
                    NotificationAction.SNOOZE -> answering.snooze(intakeId)
                    NotificationAction.OPEN -> ReminderAnswering.Response.OpenApp
                }
                notifier.dismiss(NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE))
                if (response is ReminderAnswering.Response.OpenApp) openApp(context, intakeId)
            } catch (failure: Exception) {
                // Сбой хранения или системы — не повод ронять процесс: журнал, и следующий проход повторит.
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w(TAG, "действие с уведомления не удалось", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private fun openApp(context: Context, intakeId: Uuid) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_INTAKE_ID, intakeId.toString())
        )
    }

    companion object {
        private const val TAG = "MedAppNotifications"
        const val EXTRA_INTAKE_ID = "intake_id"
    }
}
