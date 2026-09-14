package com.kert0n.medapp.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kert0n.medapp.platform.connectivity.SyncTriggers
import com.kert0n.medapp.platform.notifications.NotificationChannels
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import kotlinx.coroutines.runBlocking
import com.kert0n.medapp.queue.QueueOutbox
import com.kert0n.medapp.queue.SyncSchedule
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Точка входа графа зависимостей. Всё, что живёт дольше экрана — база, клиенты, очередь —
 * получает область приложения отсюда, а не создаётся по месту. Очередь просыпается вместе с
 * процессом: в ней могли остаться операции с прошлого запуска; вход в приложение и появившаяся
 * связь зовут синхронизацию, а регулярный фоновый заход ставится один раз (PLAN E4).
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
    lateinit var notificationSettings: NotificationSettingsSource

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
        triggers.start()
        schedule.keepRegular()
        // Проход дня — к желаемому времени сводки; вход в приложение зовёт его сразу (SyncTriggers).
        daily.keepDaily(runBlocking { notificationSettings.current() }.digestAt)
    }
}
