package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.notification.Freshness
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Заход синхронизации: отдать серверу своё и прочитать у него правду (PLAN E4). Поводов много —
 * запуск, вход в приложение, появившаяся связь, ручное обновление, регулярный фоновый заход, остаток
 * очереди, — а заход один, и повторные поводы **объединяются**: пришедший, пока заход идёт, ждёт его
 * и получает его же итог, а не заводит второе чтение того же снимка.
 *
 * **Заход принадлежит этому классу, а не тому, кто позвал первым**: он живёт в области приложения, а
 * вызывающие только ждут его итога. Ушёл с экрана тот, кто нажал «Обновить», — кончилось его
 * ожидание, а заход доживает, и итог получают остальные. Отменить заход может только конец области
 * приложения; его сбой ждущие получают сбоем, а не отменой.
 *
 * Порядок — сначала очередь, потом снимок. Отданное до чтения снимок уже видит, и коробок с
 * запросом в полёте, которые он пропускает, на обычном пути не остаётся (E1).
 *
 * После захода спрашивается база, осталось ли что-то в очереди. Осталось — планировщик системы
 * получает срок и придёт при связи, даже если процесс умрёт; пусто — ничего не ставится, и обычный
 * путь ничего не стоит.
 */
@Singleton
class Synchronization @Inject constructor(
    private val worker: QueueWorker,
    private val snapshots: SnapshotApplier,
    private val backlog: QueueBacklog,
    private val schedule: SyncSchedule,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) : Freshness {

    private val guard = Mutex()

    /** Идущий заход; законченный — повод начать новый. Читается и заводится под [guard]. */
    private var running: Deferred<Round>? = null

    private val _state = MutableStateFlow(State())

    /** Что с синхронизацией — для экрана её состояния (PLAN H3 №28). */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Заход — или присоединение к идущему. Итог один на всех, кто пришёл за время захода. Отмена
     * вызывающего снимает только его ожидание: заход живёт в области приложения.
     */
    suspend fun synchronize(): Round {
        val round = guard.withLock {
            running?.takeUnless { it.isCompleted } ?: scope.async { roundTrip() }.also { running = it }
        }
        return round.await()
    }

    /** Повод без ожидания: вход в приложение, появившаяся связь. Итог — в [state]. */
    fun request() {
        scope.launch { attempt { synchronize() } }
    }

    /**
     * Свежесть, насколько успели (PLAN D8, E4): напоминание о приёме ждёт захода не дольше [within]
     * и показывается с тем, что есть, а заход **не отменяется** — доживает в своём scope, и его итог
     * достанется следующему, кто спросит. `null` — не успели.
     */
    override suspend fun refreshBriefly(within: Duration) {
        awaitBriefly(within)
    }

    /** То же, но с итогом — проверкам важно, дождались ли захода. */
    suspend fun awaitBriefly(within: Duration): Round? =
        withTimeoutOrNull(within.toMillis()) { attempt { synchronize() }.getOrNull() }

    private suspend fun roundTrip(): Round {
        _state.update { it.copy(running = true) }
        try {
            val queue = worker.drain()
            val snapshot = snapshots.refresh()
            val now = clock.instant()
            // Пропущенные заходом строки — нечитаемые — ожиданием не доставить: за ними не приходят.
            val left = backlog.dueAt(now, except = queue.skipped.mapTo(HashSet()) { it.id })?.let { maxOf(it, now) }
            left?.let(schedule::comeBackFor)
            val round = Round(queue, snapshot, backlogDueAt = left, finishedAt = now)
            _state.update {
                State(
                    running = false,
                    lastRound = round,
                    refreshedAt = if (snapshot is SnapshotApplier.Outcome.Applied) now else it.refreshedAt
                )
            }
            return round
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    /**
     * Итог одного захода: что сделала очередь, чем кончилось чтение снимка и когда приходить за
     * оставшимся в очереди (`null` — очередь пуста).
     */
    data class Round(
        val queue: QueueWorker.Report,
        val snapshot: SnapshotApplier.Outcome,
        val backlogDueAt: Instant?,
        val finishedAt: Instant
    )

    /**
     * Идёт ли заход, чем кончился последний и когда снимок последний раз лёг. Время последнего
     * успешного чтения экран показывает человеку: ошибка чтения кэш не стирает (PLAN E4).
     */
    data class State(
        val running: Boolean = false,
        val lastRound: Round? = null,
        val refreshedAt: Instant? = null
    )
}
