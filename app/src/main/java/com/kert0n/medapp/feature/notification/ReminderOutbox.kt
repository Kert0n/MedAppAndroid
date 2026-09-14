package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
 * **Единственный владелец показа и будильника** (PLAN D8). Обязательство уже лежит в таблице;
 * забирает его не тот, кто положил, а тот, кто следит за таблицей: сигнал
 * [ReminderStorageRepository.changes] приходит после коммита по определению, и гонки «разбудили до
 * фиксации» нет (F5). Поэтому сценарий, изменивший календарь, ничего не зовёт после транзакции —
 * правило, которое нельзя было проверить, заменено механизмом.
 *
 * Проход идёт и при старте процесса — в таблице могло остаться с прошлого запуска. Границы здесь
 * же: **никто, кроме этого класса, не зовёт `Notifier` и `ReminderAlarms`** ([NotificationOwnershipTest]);
 * что должно быть обещано, решает [NotificationReconciliation] и в показ не лезет.
 */
@Singleton
class ReminderOutbox @Inject constructor(
    private val reminders: ReminderStorageRepository,
    private val notifier: Notifier,
    private val alarms: ReminderAlarms,
    private val freshness: Freshness,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Просьбы о проходе сворачиваются: сколько бы сигналов ни пришло, следующий проход один. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(State())

    /** Что происходит с доставкой — для экрана состояния (PLAN H3 №28). */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { reminders.changes().collect { wake.trySend(Unit) } }
        scope.launch { run() }
    }

    /** Повод без ожидания: сработал будильник, вошли в приложение, загрузилось устройство. */
    fun runNow() {
        if (wake.trySend(Unit).isSuccess) return
        scope.launch { runCatching { pass() } }
    }

    private suspend fun run() {
        wake.trySend(Unit)
        for (signal in wake) {
            try {
                pass()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Сбой прохода не роняет процесс: он записан в состоянии, и следующий повод повторит.
                _state.update { it.copy(lastFailure = failure.toString()) }
            }
        }
    }

    /**
     * Один проход: погасить отозванное, сказать наступившее, прибрать давнее и переставить
     * будильник на ближайший срок. Перед напоминанием о приёме — короткая сверка с сервером, чтобы
     * остаток на карточке был свежим, насколько успели (PLAN D8, E4).
     */
    suspend fun pass(): Report {
        val dismissed = dismissWithdrawn()
        val now = clock.instant()
        val due = reminders.due(now, NoticeDelivery.SYSTEM)
        if (due.any { it.kind == NotificationKind.INTAKE_DUE }) freshness.refreshBriefly(REFRESH_WAIT)
        var shown = 0
        for (reminder in due) {
            if (!notifier.show(reminder)) continue
            // Отметка — **после** показа: падение между ними оставляет обязательство невыполненным.
            reminders.markShown(reminder.key, clock.instant())
            shown++
        }
        reminders.forgetShownBefore(now.minus(RETENTION))
        val next = wakeForTheNearest()
        val report = Report(shown = shown, dismissed = dismissed, nextAt = next)
        _state.update { it.copy(passes = it.passes + 1, lastReport = report, lastFailure = null) }
        return report
    }

    /** Повода больше нет: гасим показанное и забываем. Система не откатывается вместе с базой (F5). */
    private suspend fun dismissWithdrawn(): Int {
        val withdrawn = reminders.withdrawn()
        for (reminder in withdrawn) notifier.dismiss(reminder.key)
        reminders.forget(withdrawn.map { it.key })
        return withdrawn.size
    }

    /** Будильник — на самое раннее невыполненное обязательство; нет такого — будить незачем. */
    private suspend fun wakeForTheNearest(): Instant? {
        val next = reminders.nextDue(NoticeDelivery.SYSTEM)
        if (next == null) alarms.stopWaking() else alarms.wakeAt(next.dueAt, next.exact)
        return next?.dueAt
    }

    /** Чем кончился проход: сколько сказано, сколько погашено и когда просыпаться. */
    data class Report(val shown: Int, val dismissed: Int, val nextAt: Instant?)

    /** Сколько проходов было, чем кончился последний и когда следующий по сроку. */
    data class State(
        val passes: Int = 0,
        val lastReport: Report? = null,
        val lastFailure: String? = null
    )

    companion object {
        /** Дольше напоминание не ждёт: пара секунд — и показ с тем, что есть (PLAN D8). */
        val REFRESH_WAIT: Duration = Duration.ofSeconds(2)

        /** Сказанное давно забывается: иначе таблица растёт всю жизнь установки. */
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
