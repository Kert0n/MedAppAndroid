package com.kert0n.medapp.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kert0n.medapp.platform.connectivity.SyncTriggers
import com.kert0n.medapp.platform.notifications.NotificationChannels
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
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Каналы — до первого уведомления: человек выключает их по отдельности (PLAN D8).
        channels.ensure()
        outbox.start()
        triggers.start()
        schedule.keepRegular()
    }
}
