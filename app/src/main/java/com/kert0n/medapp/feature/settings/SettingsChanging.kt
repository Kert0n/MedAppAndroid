package com.kert0n.medapp.feature.settings

import com.kert0n.medapp.feature.notification.NotificationReconciliation
import java.time.Clock
import javax.inject.Inject

/**
 * Человек изменил настройки (PLAN D8, E4): записать и **применить сразу** — изменение, которое
 * подействует с завтрашнего прохода, выглядит как не подействовавшее. Единственный, кто пишет
 * настройки; читают их все через [SettingsStore].
 *
 * Применение об уведомлениях — сверка: включённое обещается, выключенное снимается, сводка
 * переносится на новое время. Что не легло — не применяется: прежние настройки действуют дальше.
 */
class SettingsChanging @Inject constructor(
    private val store: SettingsStore,
    private val reconciliation: NotificationReconciliation,
    private val clock: Clock
) {

    suspend fun change(settings: AppSettings): Outcome {
        val before = store.current()
        if (store.save(settings) == SettingsSaved.LOST) return Outcome.NOT_SAVED
        if (before.notifications != settings.notifications) reconciliation.reconcile(clock.instant(), clock.zone)
        return Outcome.SAVED
    }

    enum class Outcome { SAVED, NOT_SAVED }
}
