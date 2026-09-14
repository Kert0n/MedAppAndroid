package com.kert0n.medapp.platform.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kert0n.medapp.feature.course.CourseUpkeep
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.queue.Synchronization
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.Duration

/**
 * Заход синхронизации, когда процесс поднимает система, а не человек (PLAN E4). Делает то же, что
 * вход в приложение, — через тот же координатор, поэтому совпавшие поводы не читают снимок дважды.
 *
 * Заходов два, и ведут они себя по-разному:
 *
 * - **регулярный** — узнать чужие изменения, пока приложение закрыто. Если снимок недавно лёг —
 *   человек заходил, — заход пропускается: регулярность нужна, когда никто не смотрит;
 * - **за остатком** — в очереди осталось неотправленное. Пока оно есть, заход просит систему
 *   повторить при связи: принятое в метро не должно ждать, пока приложение откроют снова.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val synchronization: Synchronization,
    private val upkeep: CourseUpkeep,
    private val settings: SettingsStore,
    private val clock: Clock
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val comeBack = inputData.getBoolean(COME_BACK, false)
        // Календарь приводится в порядок при любом заходе: это локально, и недавний снимок его не
        // заменяет — день мог кончиться с тех пор (PLAN F4).
        upkeep.keepUp()
        if (!comeBack && refreshedRecently()) return Result.success()
        val round = synchronization.synchronize()
        return if (comeBack && round.backlogDueAt != null) Result.retry() else Result.success()
    }

    /** «Недавно» — половина выбранного интервала: регулярность нужна, когда никто не смотрит (PLAN E4). */
    private suspend fun refreshedRecently(): Boolean {
        val refreshedAt = synchronization.state.value.refreshedAt ?: return false
        return Duration.between(refreshedAt, clock.instant()) < settings.current().syncInterval.duration.dividedBy(2)
    }

    companion object {
        const val COME_BACK = "come_back"
    }
}
