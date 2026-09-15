package com.kert0n.medapp.queue

import com.kert0n.medapp.di.ApplicationScope
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Единственный владелец фоновой отправки. Команда уже лежит в таблице операций; забирает её не
 * тот, кто положил, а тот, кто следит за таблицей: сигнал [QueueStorage.changes] приходит после
 * коммита по определению (PLAN E4, F5). Проход идёт и при старте процесса — в очереди могло
 * остаться с прошлого запуска, — и по ближайшему `not_before`, о котором после каждого прохода
 * спрашивают базу: память прохода знает только то, что он трогал, а отложенная операция лежит в
 * таблице. Цикл — [OutboxLoop]: сигналы сворачиваются, сбой прохода записан и повторяется по сроку.
 * Сбой одной операции работник изолирует сам ([QueueWorker.Report.failed]).
 */
@Singleton
class QueueOutbox @Inject constructor(
    private val worker: QueueWorker,
    private val storage: QueueStorage,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope
) {

    private val lastReport = MutableStateFlow<QueueWorker.Report?>(null)

    private val loop = OutboxLoop(storage.changes(), RETRY_AFTER_FAILURE.toJavaDuration(), clock, scope) {
        lastReport.value = worker.drain()
        storage.nextDueAt(clock.instant())
    }

    /** Что происходит с отправкой — для экрана состояния синхронизации (PLAN H3 №28). */
    val state: StateFlow<State> = combine(loop.state, lastReport) { loop, report ->
        State(passes = loop.passes, lastReport = report, lastFailure = loop.lastFailure, nextRunAt = loop.nextRunAt)
    }.stateIn(scope, SharingStarted.Eagerly, State())

    /** Наблюдатель таблицы встал — для проверок, которые ждут именно сигнала. */
    val ready: StateFlow<Boolean> get() = loop.ready

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() = loop.start()

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
