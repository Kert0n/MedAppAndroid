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
import com.kert0n.medapp.queue.SyncInterval
import com.kert0n.medapp.queue.SyncSchedule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.first

/**
 * Заходы без человека — планировщиком системы (PLAN E4). Он не обещает точного времени и сам
 * дожидается связи: заход без неё бессмыслен, а с ней система поднимет процесс, даже если его убили.
 *
 * Оба захода уникальны по имени. Регулярный ставится с интервалом из настроек: тот же интервал
 * ничего не трогает — задача не сдвигается каждым запуском, — а другой обновляет **ту же** задачу,
 * и следующий заход считается от последнего с новым шагом. За остатком приходят один раз — пока
 * он есть, заход сам просит повтора.
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

    override suspend fun keepRegular(interval: SyncInterval) {
        val standing = work.getWorkInfosForUniqueWorkFlow(REGULAR).first().firstOrNull { !it.state.isFinished }
        if (standing?.periodicityInfo?.repeatIntervalMillis == interval.duration.toMillis()) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.duration)
            .setConstraints(online)
            .build()
        work.enqueueUniquePeriodicWork(REGULAR, ExistingPeriodicWorkPolicy.UPDATE, request)
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
        /** Первая пауза повтора за остатком; дальше система удваивает её сама. */
        val COME_BACK_BACKOFF: Duration = Duration.ofSeconds(30)

        const val REGULAR = "sync-regular"
        const val COME_BACK = "sync-come-back"
    }
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
