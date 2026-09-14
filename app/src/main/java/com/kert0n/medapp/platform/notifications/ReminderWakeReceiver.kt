package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kert0n.medapp.feature.notification.ReminderOutbox
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                outbox.pass()
            } catch (failure: Exception) {
                // Сбой не повод ронять процесс: проход сам назначил себе срок возврата.
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w(TAG, "проход по будильнику не удался", failure)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MedAppNotifications"
        const val ACTION = "com.kert0n.medapp.REMINDERS_DUE"
    }
}
