package com.kert0n.medapp.platform.notifications

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.platform.AppEntries
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first

/**
 * Ежедневный проход планировщиком системы (PLAN D8): периодическая задача раз в сутки с первой
 * задержкой до желаемого времени сводки в зоне устройства. Уникальна: стоящая на то же время в
 * той же зоне не трогается, на другое — ставится заново с новой задержкой; что зона сменилась,
 * замечает проход дня (`DailyWorker`) и переставляет её сам. Время, к которому её ставили, задача
 * несёт меткой: спрашивать о нём планировщик — значит пересчитывать его срок обратно в часы, а он
 * считает от своих часов. Обновить задачу на месте нельзя: следующий запуск планировщик считает от
 * прежней постановки, и новая задержка в этот счёт не входит. Проход «сейчас» — разовая задача,
 * заменяющая предыдущую такую же.
 */
class WorkManagerDailySchedule @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val clock: Clock,
    private val entries: AppEntries
) : DailySchedule {

    private val work: WorkManager get() = workManager.get()

    override suspend fun keepDaily(at: LocalTime) {
        val standing = work.getWorkInfosForUniqueWorkFlow(DAILY).first().firstOrNull { !it.state.isFinished }
        // Задача прошлой сборки будит её работника — оставлять её нельзя и с тем же временем.
        if (standing != null && atTag(at) in standing.tags && entries.daily.name in standing.tags) return
        val now = clock.instant().atZone(clock.zone)
        var first = now.with(at)
        if (!first.isAfter(now)) first = first.plusDays(1)
        val request = PeriodicWorkRequest.Builder(entries.daily, Duration.ofDays(1))
            .setInitialDelay(Duration.between(now, first))
            .addTag(atTag(at))
            .build()
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    /**
     * К какому времени **в какой зоне** поставлена: метка, а не пересчёт срока планировщика.
     * Сменилась зона — «09:00» уже другой момент, и задача с прежней меткой не та же (C1 «Часы
     * устройства — в его нынешней зоне»).
     */
    private fun atTag(at: LocalTime): String = "$AT${at.truncatedTo(ChronoUnit.MINUTES)}@${clock.zone.id}"

    override fun runNow() {
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequest.Builder(entries.daily).build())
    }

    companion object {
        const val DAILY = "notifications-daily"
        const val NOW = "notifications-now"
        private const val AT = "notifications-daily-at:"
    }
}
