package com.kert0n.medapp.queue

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Цикл владельца — один на всех, кто живёт от сигнала после коммита: очередь отправки, владелец
 * доставки, сверка обещанного (PLAN E4, D8, C1 «Цикл владельца — один»). Сигнал [signals]
 * приходит после коммита по определению, и гонки «разбудили до фиксации» нет; сколько бы
 * сигналов ни пришло за проход, следующий проход один; проход один за раз.
 *
 * Проход отвечает, **когда прийти снова** — срок из базы или `null`, если ждать нечего, — и цикл
 * ставит на него таймер. Сбой прохода — тоже срок: он записан в [state], и цикл возвращается не
 * позже [retryAfterFailure], а не молчит до следующего сигнала. Что переживёт смерть процесса —
 * будильник, планировщик — ставит сам владелец: цикл живёт с процессом.
 *
 * [ready] — наблюдатель сигналов встал: запись, сделанная раньше, сигнала не даст, и тому, кто
 * ждёт именно сигнала (проверки), есть чего дождаться, а не сколько-то миллисекунд. Готовность
 * приносит сам поток: его **первое** значение — «наблюдатель на месте», а не изменение (у Room
 * это `emitInitialState`, и приходит оно после того, как триггеры таблиц поставлены).
 */
class OutboxLoop(
    private val signals: Flow<Unit>,
    private val retryAfterFailure: Duration,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val initialPass: Boolean = true,
    private val pass: suspend () -> Instant?
) {

    private val started = AtomicBoolean(false)

    /** Просьбы о проходе сворачиваются: сколько бы сигналов ни пришло, следующий проход один. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _ready = MutableStateFlow(false)

    /** Наблюдатель сигналов встал: следующая запись после коммита будет услышана. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _state = MutableStateFlow(State())

    /** Сколько проходов было, чем кончился последний и когда следующий по сроку. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var first = true
            signals.collect {
                if (first) {
                    first = false
                    _ready.value = true
                } else {
                    wake.trySend(Unit)
                }
            }
        }
        scope.launch { run() }
    }

    /** Повод без ожидания: сработал будильник, вошли в приложение, загрузилось устройство. */
    fun runNow() {
        wake.trySend(Unit)
    }

    private suspend fun run() {
        if (initialPass) wake.trySend(Unit)
        var timer: Job? = null
        for (signal in wake) {
            timer?.cancel()
            val next = try {
                val at = pass()
                _state.update { it.copy(passes = it.passes + 1, lastFailure = null, nextRunAt = at) }
                at
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Сбой прохода не роняет процесс и не оставляет цикл ждать неизвестно чего.
                val at = clock.instant().plus(retryAfterFailure)
                _state.update { it.copy(passes = it.passes + 1, lastFailure = failure.toString(), nextRunAt = at) }
                at
            }
            timer = next?.let { at ->
                scope.launch {
                    delay(Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO).toMillis())
                    wake.trySend(Unit)
                }
            }
        }
    }

    /** Сколько проходов было, чем кончился последний (`null` — удачно) и когда следующий по сроку. */
    data class State(
        val passes: Int = 0,
        val lastFailure: String? = null,
        val nextRunAt: Instant? = null
    )
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
