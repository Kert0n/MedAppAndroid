package com.kert0n.medapp.platform.notifications

import android.content.BroadcastReceiver
import android.util.Log
import com.kert0n.medapp.feature.notification.ReminderOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Проход владельца доставки, пока приёмник **держит процесс** (`goAsync()`): сказать наступившее и
 * поставить будильник успевают раньше, чем система вправе процесс убить (PLAN D8). Приёмник,
 * вернувшийся раньше, оставил бы приложение без единого живого компонента, а в процессе, который
 * система подняла ради сигнала, цикла владельца может и не быть.
 *
 * `goAsync()` вне настоящей рассылки — при прямом вызове `onReceive` — отвечает `null`: тогда
 * держать нечего, и проход просто идёт.
 */
internal fun BroadcastReceiver.passHoldingTheProcess(outbox: ReminderOutbox, failure: String) {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            outbox.pass()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            // Сбой не повод ронять процесс: проход сам назначил себе срок возврата.
            Log.w(TAG, failure, thrown)
        } finally {
            pending?.finish()
        }
    }
}

private const val TAG = "MedAppNotifications"
