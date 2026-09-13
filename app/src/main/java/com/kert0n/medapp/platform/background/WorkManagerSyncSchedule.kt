package com.kert0n.medapp.platform.background

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kert0n.medapp.queue.SyncSchedule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

/**
 * Заходы без человека — планировщиком системы (PLAN E4). Он не обещает точного времени и сам
 * дожидается связи: заход без неё бессмыслен, а с ней система поднимет процесс, даже если его убили.
 *
 * Оба захода уникальны по имени и при повторной постановке сохраняются: регулярный не сдвигается
 * каждым запуском, а за остатком приходят один раз — пока он есть, заход сам просит повтора.
 */
class WorkManagerSyncSchedule @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val clock: Clock
) : SyncSchedule {

    /**
     * Берётся при первой постановке, а не при сборке графа: WorkManager настраивается приложением
     * (`MedApp`), и спрошенный раньше, чем приложение внедрило фабрику задач, он её не получил бы.
     */
    private val work: WorkManager get() = workManager.get()

    override fun keepRegular() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(REGULAR_INTERVAL)
            .setConstraints(online)
            .build()
        work.enqueueUniquePeriodicWork(REGULAR, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun comeBackFor(dueAt: Instant) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(Duration.between(clock.instant(), dueAt).coerceAtLeast(Duration.ZERO))
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, COME_BACK_BACKOFF)
            .setInputData(workDataOf(SyncWorker.COME_BACK to true))
            .build()
        work.enqueueUniqueWork(COME_BACK, ExistingWorkPolicy.KEEP, request)
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        /**
         * Как часто узнавать чужие изменения, пока приложение закрыто. Журнал повторов сервера живёт
         * не дольше суток (B6), и заходить реже нельзя; настройками станет от 15 минут до 8 часов (H3).
         */
        val REGULAR_INTERVAL: Duration = Duration.ofHours(1)

        /** Первая пауза повтора за остатком; дальше система удваивает её сама. */
        val COME_BACK_BACKOFF: Duration = Duration.ofSeconds(30)

        const val REGULAR = "sync-regular"
        const val COME_BACK = "sync-come-back"
    }
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
