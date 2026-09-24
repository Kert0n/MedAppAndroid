package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.queue.OutboxLoop
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * **Когда сверять** — и только это (PLAN D8). Обещанное следует из состояния коробок, лечений,
 * пунктов и очереди; изменилось состояние — обещанное надо сверить, кто бы его ни изменил:
 * человек правкой срока, снимок чужим расходом, работник закрытием операции. Повод приходит
 * сигналом [ReminderRecords.groundsChanged] после коммита, поэтому сценарий после своей
 * транзакции ничего не зовёт — цикл тот же, что у [ReminderOutbox] и `QueueOutbox` ([OutboxLoop]):
 * сигналы сворачиваются, сбой сверки записан и повторяется по сроку, а не ждёт чужого сигнала.
 *
 * Старт процесса — тоже повод (C1 «Сверка при старте процесса — тоже повод»): основание могло
 * измениться перед тем, как процесс умер, а сверка не успела; процесс, поднятый будильником или
 * работником, входом в приложение не является, и [DailyRound] его не зовёт. Начальный проход
 * сверяет по состоянию, каким оно лежит сейчас, — повторная сверка без изменений молчит.
 * [DailyRound] остаётся поводом **времени**: смена дня таблиц не меняет. Что должно быть обещано,
 * решает [NotificationReconciliation]; показывать и будить умеет только [ReminderOutbox].
 */
@Singleton
class NotificationUpkeep @Inject constructor(
    reminders: ReminderRecords,
    private val reconciliation: NotificationReconciliation,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope
) {

    private val lastReport = MutableStateFlow<NotificationReconciliation.Report?>(null)

    private val loop = OutboxLoop(reminders.groundsChanged(), RETRY_AFTER_FAILURE, clock, scope) {
        lastReport.value = reconciliation.reconcile(clock.instant(), clock.zone)
        null
    }

    /** Сколько сверок было и чем кончилась последняя — для экрана состояния (PLAN H3 №28). */
    val state: StateFlow<State> = combine(loop.state, lastReport) { loop, report ->
        State(sweeps = loop.passes, lastReport = report, lastFailure = loop.lastFailure, nextRunAt = loop.nextRunAt)
    }.stateIn(scope, SharingStarted.Eagerly, State())

    /** Наблюдатель оснований встал — для проверок, которые ждут именно сигнала. */
    val ready: StateFlow<Boolean> get() = loop.ready

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() = loop.start()

    /** Сколько сверок по сигналу было, чем кончилась последняя и когда повтор после сбоя. */
    data class State(
        val sweeps: Int = 0,
        val lastReport: NotificationReconciliation.Report? = null,
        val lastFailure: String? = null,
        val nextRunAt: java.time.Instant? = null
    )

    companion object {
        /** Сбой сверки — тоже срок: не позже этого она повторяется сама (довод `QueueOutbox`). */
        val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(1)
    }
}
