package com.kert0n.medapp.platform.notifications

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kert0n.medapp.feature.notification.DailySchedule
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Provider

/**
 * Ежедневный проход планировщиком системы (PLAN D8): периодическая задача раз в сутки с первой
 * задержкой до желаемого времени сводки в зоне устройства; уникальна и при повторной постановке
 * сохраняется. Проход «сейчас» — разовая задача, заменяющая предыдущую такую же.
 */
class WorkManagerDailySchedule @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val clock: Clock
) : DailySchedule {

    private val work: WorkManager get() = workManager.get()

    override fun keepDaily(at: LocalTime) {
        val now = clock.instant().atZone(clock.zone)
        var first = now.with(at)
        if (!first.isAfter(now)) first = first.plusDays(1)
        val request = PeriodicWorkRequestBuilder<DailyWorker>(Duration.ofDays(1))
            .setInitialDelay(Duration.between(now, first))
            .build()
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun runNow() {
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DailyWorker>().build())
    }

    companion object {
        const val DAILY = "notifications-daily"
        const val NOW = "notifications-now"
    }
}
