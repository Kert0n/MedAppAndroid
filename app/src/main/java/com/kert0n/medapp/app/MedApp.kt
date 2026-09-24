package com.kert0n.medapp.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.feature.delivery.SyncSchedule
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.notification.NotificationUpkeep
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.platform.connectivity.SyncTriggers
import com.kert0n.medapp.platform.notifications.NotificationChannels
import com.kert0n.medapp.queue.QueueOutbox
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Точка входа графа зависимостей. Всё, что живёт дольше экрана — база, клиенты, очередь —
 * получает область приложения отсюда, а не создаётся по месту. Очередь просыпается вместе с
 * процессом: в ней могли остаться операции с прошлого запуска; вход в приложение и появившаяся
 * связь зовут синхронизацию, а регулярный фоновый заход ставится по настройкам (PLAN E4).
 *
 * Фоновые задачи собираются графом, поэтому WorkManager настраивается здесь, а не сам по себе.
 */
@HiltAndroidApp
class MedApp : Application(), Configuration.Provider {

    @Inject
    lateinit var outbox: QueueOutbox

    @Inject
    lateinit var triggers: SyncTriggers

    @Inject
    lateinit var schedule: SyncSchedule

    @Inject
    lateinit var channels: NotificationChannels

    @Inject
    lateinit var daily: DailySchedule

    @Inject
    lateinit var reminderOutbox: ReminderOutbox

    @Inject
    lateinit var notificationUpkeep: NotificationUpkeep

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Каналы — до первого уведомления: человек выключает их по отдельности (PLAN D8).
        channels.ensure()
        outbox.start()
        // Владелец показа и будильника: в таблице могло остаться с прошлого запуска (PLAN D8).
        reminderOutbox.start()
        // Сверка обещанного — по сигналу изменившихся оснований, а не по вызову из сценария (PLAN D8).
        notificationUpkeep.start()
        triggers.start()
        // Задачи планировщика — по настройкам, и с теми же настройками они не пересоздаются;
        // проход дня — к желаемому времени сводки, вход в приложение зовёт его сразу (SyncTriggers).
        scope.launch {
            val current = settings.current()
            schedule.keepRegular(current.syncInterval)
            daily.keepDaily(current.notifications.digestAt)
        }
    }
}
