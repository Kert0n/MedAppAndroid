package com.kert0n.medapp.platform.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.feature.settings.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Проход дня, когда процесс поднимает система: то же, что делает вход в приложение (PLAN D8), и
 * проход владельца доставки следом — будильник встаёт, пока работа держит процесс.
 * После прохода — постановка себя же: ежедневная задача стоит на времени **в зоне**, и проход дня
 * следит, что она стоит верно, — все поводы времени (загрузка, смена зоны, перевод часов) ведут
 * сюда (C1 «Часы устройства — в его нынешней зоне»). Та же постановка ничего не трогает.
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val round: DailyRound,
    private val outbox: ReminderOutbox,
    private val daily: DailySchedule,
    private val settings: SettingsStore
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        round.run()
        // Работа — единственное, что держит процесс, поднятый системой: к её концу будильник к
        // ближайшему обещанию обязан стоять, а не ждать цикла, которого может не оказаться.
        outbox.pass()
        daily.keepDaily(settings.current().notifications.digestAt)
        return Result.success()
    }
}
