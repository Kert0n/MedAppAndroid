package com.kert0n.medapp.platform.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kert0n.medapp.feature.notification.DailyRound
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Проход дня, когда процесс поднимает система: то же, что делает вход в приложение (PLAN D8). */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val round: DailyRound
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        round.run()
        return Result.success()
    }
}
