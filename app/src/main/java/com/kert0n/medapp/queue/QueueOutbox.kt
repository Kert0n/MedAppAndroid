package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.di.ApplicationScope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Единственный владелец фоновой отправки. Команда уже лежит в таблице операций; забирает её не
 * тот, кто положил, а тот, кто следит за таблицей: сигнал [QueueStorage.changes] приходит после
 * коммита по определению, и гонки «разбудили до фиксации» нет (PLAN E4, F5). Проход идёт и при
 * старте процесса — в очереди могло остаться с прошлого запуска, — и по ближайшему
 * `not_before`, о котором после каждого прохода спрашивают базу: память прохода знает только то,
 * что он трогал, а отложенная операция лежит в таблице. Сигналы, пришедшие во время прохода,
 * сворачиваются в один следующий — в том числе сигнал от собственной записи.
 *
 * Здесь же политика ошибок: исключение прохода не роняет процесс — оно записано в [state], и
 * очередь пробуется снова через [RETRY_AFTER_FAILURE]. Сбой одной операции работник изолирует
 * сам ([QueueWorker.Report.failed]).
 */
@Singleton
class QueueOutbox @Inject constructor(
    private val worker: QueueWorker,
    private val storage: QueueStorage,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Просьбы о проходе сворачиваются: сколько бы сигналов ни пришло, следующий проход один. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(State())

    /** Что происходит с отправкой — для экрана состояния синхронизации (PLAN H3 №28). */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { storage.changes().collect { wake.trySend(Unit) } }
        scope.launch { run() }
    }

    private suspend fun run() {
        wake.trySend(Unit)
        var timer: Job? = null
        for (signal in wake) {
            timer?.cancel()
            val nextRunAt = pass()
            _state.update { it.copy(nextRunAt = nextRunAt) }
            timer = nextRunAt?.let { at ->
                scope.launch {
                    delay(Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO).toMillis())
                    wake.trySend(Unit)
                }
            }
        }
    }

    /**
     * Один проход и ближайший срок после него — из базы; `null` — приходить не надо, пока таблица
     * не изменится. Сбой прохода — тоже срок: не позже [RETRY_AFTER_FAILURE].
     */
    private suspend fun pass(): Instant? = try {
        val report = worker.drain()
        _state.update { it.copy(passes = it.passes + 1, lastReport = report, lastFailure = null) }
        storage.nextDueAt(clock.instant())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        _state.update { it.copy(passes = it.passes + 1, lastFailure = failure.toString()) }
        val retryAt = clock.instant().plus(RETRY_AFTER_FAILURE.toJavaDuration())
        val due = attempt { storage.nextDueAt(clock.instant()) }.getOrNull()
        if (due != null && due.isBefore(retryAt)) due else retryAt
    }

    /** Сколько проходов было, чем кончился последний, и когда следующий по сроку. */
    data class State(
        val passes: Int = 0,
        val lastReport: QueueWorker.Report? = null,
        val lastFailure: String? = null,
        val nextRunAt: Instant? = null
    )

    companion object {
        /** Проход упал целиком — база или транспорт бросили мимо работника: пробуем через минуту. */
        val RETRY_AFTER_FAILURE = 1.minutes
    }
}
