package com.kert0n.medapp.platform.notifications

import com.kert0n.medapp.domain.attempt
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
 * Действие с уведомления о приёме (PLAN D8): «Пропустить» — `IntakeDeclining` тем же вызовом, что
 * экран; «Отложить» сдвигает срок обязательства.
 *
 * **Активность отсюда не запускается.** Приёмник, поднятый нажатием на уведомление, с Android 12
 * этого не может — платформа зовёт это notification trampoline и запуск блокирует (C1). Поэтому в
 * шторке нет «Принял»: он требует экрана при просрочке, отменённом курсе и затронутых бронях, а
 * гасить карточку, ничего не записав, хуже, чем не иметь кнопки. Он вернётся вместе с экраном
 * предупреждения в U5.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var answering: ReminderAnswering

    @Inject
    lateinit var notifier: Notifier

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.let { name -> NotificationAction.entries.firstOrNull { it.name == name } } ?: return
        val intakeId = intent.getStringExtra(EXTRA_INTAKE_ID)?.let { attempt { Uuid.parse(it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    NotificationAction.SKIP -> answering.skip(intakeId)
                    NotificationAction.SNOOZE -> answering.snooze(intakeId)
                }
                // Человек нажал — карточка уходит сразу, ждать прохода владельца незачем.
                notifier.dismiss(NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE))
            } catch (failure: Exception) {
                // Сбой хранения или системы — не повод ронять процесс: журнал, и следующий проход повторит.
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w(TAG, "действие с уведомления не удалось", failure)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MedAppNotifications"
        const val EXTRA_INTAKE_ID = "intake_id"
    }
}
