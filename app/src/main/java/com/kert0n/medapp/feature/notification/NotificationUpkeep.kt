package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * **Когда сверять** — и только это (PLAN D8). Обещанное следует из состояния коробок, лечений,
 * пунктов и очереди; изменилось состояние — обещанное надо сверить, кто бы его ни изменил:
 * человек правкой срока, снимок чужим расходом, работник закрытием операции. Повод приходит
 * сигналом [ReminderStorageRepository.groundsChanged] после коммита, поэтому сценарий после своей
 * транзакции ничего не зовёт — тот же механизм, что у [ReminderOutbox] и `QueueOutbox` (F5).
 *
 * Сигналы сворачиваются: сколько бы таблиц ни изменилось одной укладкой, сверка одна. Начального
 * прохода нет — вход в приложение зовёт [DailyRound], и он же остаётся поводом **времени**: смена
 * дня таблиц не меняет. Что должно быть обещано, решает [NotificationReconciliation]; показывать и
 * будить умеет только [ReminderOutbox].
 */
@Singleton
class NotificationUpkeep @Inject constructor(
    private val reminders: ReminderStorageRepository,
    private val reconciliation: NotificationReconciliation,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Просьбы о сверке сворачиваются: сколько бы сигналов ни пришло, следующая сверка одна. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(State())

    /** Сколько сверок было и чем кончилась последняя — для экрана состояния (PLAN H3 №28). */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { reminders.groundsChanged().collect { wake.trySend(Unit) } }
        scope.launch { for (signal in wake) sweep() }
    }

    private suspend fun sweep() {
        try {
            val report = reconciliation.reconcile(clock.instant(), clock.zone)
            _state.update { it.copy(sweeps = it.sweeps + 1, lastReport = report, lastFailure = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Сбой сверки не роняет процесс: он записан, а следующий сигнал или проход дня повторит.
            _state.update { it.copy(sweeps = it.sweeps + 1, lastFailure = failure.toString()) }
        }
    }

    /** Сколько сверок по сигналу было, чем кончилась последняя. */
    data class State(
        val sweeps: Int = 0,
        val lastReport: NotificationReconciliation.Report? = null,
        val lastFailure: String? = null
    )
}
