package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.Delivery
import com.kert0n.medapp.domain.notification.Reminder
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val transactions: com.kert0n.medapp.queue.Transactions,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Проход один за раз: цикл и приёмник будильника не показывают одно и то же дважды. */
    private val passes = Mutex()

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
        scope.launch { attempt { pass() } }
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
     * будильник. Перед напоминанием о приёме — короткая сверка с сервером, чтобы остаток на
     * карточке был свежим, насколько успели (PLAN D8, E4).
     *
     * Под замком: два входа — цикл и приёмник будильника — не должны идти одновременно, иначе один
     * покажет то, что другой уже пометил сказанным.
     */
    suspend fun pass(): Report = passes.withLock {
        try {
            attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Сбой прохода — тоже срок: мы вернёмся, а не замолчим до входа в приложение (E4).
            _state.update { it.copy(passes = it.passes + 1, lastFailure = failure.toString()) }
            val retryAt = clock.instant().plus(RETRY_AFTER_FAILURE)
            attempt { alarms.wakeAt(retryAt, exact = false) }
            Report(shown = 0, dismissed = 0, blocked = 0, nextAt = retryAt)
        }
    }

    private suspend fun attempt(): Report {
        var dismissed = dismissWithdrawn()
        val now = clock.instant()
        // Ожидание свежести — окно до двух секунд, и прочитанное до него устаревает: человек
        // успевает отложить, лечение — отмениться. Показывается то, что наступило **после** него.
        if (due(now).any { it.kind == NotificationKind.INTAKE_DUE }) freshness.refreshBriefly(REFRESH_WAIT)
        val due = due(now)

        var shown = 0
        var blocked = 0
        for (reminder in due) {
            // Сбой одного показа не уносит остальные: работник очереди изолирует свои так же (E4).
            val outcome = attempt { notifier.show(reminder) }.getOrElse { Delivery.FAILED }
            if (outcome == Delivery.NOT_ALLOWED) {
                // Показать нечем: обязательство ждёт листа приёмов, и будильника оно не попросит.
                blocked++
                continue
            }
            when (settle(reminder.key, outcome)) {
                Settled.RECORDED -> if (outcome == Delivery.SHOWN) shown++
                // Пока система показывала, обязательство изменилось: карточка висит без основания.
                Settled.OUTDATED -> if (outcome == Delivery.SHOWN) {
                    attempt { notifier.dismiss(reminder.key) }
                    dismissed++
                }
            }
        }
        forgetThePast(now)

        // Будильник ставится по **свежему** чтению: за проход обязательства изменились.
        val left = reminders.awaiting(NoticeDelivery.SYSTEM)
        val next = left.mapNotNull { it.wakeAt(clock.instant()) }.minOrNull()
        if (next == null) alarms.stopWaking() else alarms.wakeAt(next, exact = exactnessOf(left, next))
        val report = Report(shown = shown, dismissed = dismissed, blocked = blocked, nextAt = next)
        _state.update { it.copy(passes = it.passes + 1, lastReport = report, lastFailure = null, pushBlocked = blocked > 0) }
        return report
    }

    /** Наступившее и несказанное этим способом доставки — свежим чтением. */
    private suspend fun due(now: Instant): List<Reminder> =
        reminders.awaiting(NoticeDelivery.SYSTEM).filter { it.isDue(now) }

    /**
     * Записать исход показа — **перечитав** обязательство своей транзакцией. Пока шла система,
     * лечение могли отменить, человек мог отложить. Писать прочитанное значило бы воскресить снятое
     * и потерять отсрочку (F5, C1).
     *
     * Изменилось — решение приняли без нас, и оно свежее: исход не записывается, а вызывающий
     * гасит показ, оставшийся без основания. «Следующий проход поправит» здесь не работает: он
     * снимает отозванное, а отложенное остаётся обещанным на новый срок — с карточкой в шторке.
     */
    private suspend fun settle(key: com.kert0n.medapp.domain.notification.NotificationKey, outcome: Delivery): Settled =
        transactions.run {
            val fresh = reminders.find(key) ?: return@run Settled.OUTDATED
            val at = clock.instant()
            if (!fresh.isDue(at)) return@run Settled.OUTDATED
            when (outcome) {
                Delivery.SHOWN -> fresh.deliveredAt(at)
                Delivery.SUBJECT_GONE -> fresh.withdraw()
                Delivery.FAILED -> fresh.failedAt(at)
                Delivery.NOT_ALLOWED -> return@run Settled.OUTDATED
            }
            reminders.saveAll(listOf(fresh))
            Settled.RECORDED
        }

    /** Исход записан — либо обязательство изменилось, пока система показывала, и показ устарел. */
    private enum class Settled { RECORDED, OUTDATED }

    /** Точность просит тот, чей срок настал: напоминанию о приёме она нужна, остальному нет. */
    private fun exactnessOf(awaiting: List<Reminder>, next: java.time.Instant): Boolean =
        awaiting.any { it.exact && it.wakeAt(next) == null && it.dueAt <= next }

    /**
     * Повода больше нет: гасим показанное и забываем. Система не откатывается вместе с базой (F5),
     * поэтому гашение идёт по прочитанному, а удаление — **перечитав** в своей транзакции: пока
     * система гасила, повод мог вернуться, и `promise` воскресил обязательство под тем же ключом.
     * Удалить его значило бы потерять живое; гашение воскрешённого безвредно — сказано о нём не было.
     */
    private suspend fun dismissWithdrawn(): Int {
        val withdrawn = reminders.withdrawn()
        for (reminder in withdrawn) notifier.dismiss(reminder.key)
        transactions.run {
            reminders.deleteAll(
                reminders.findAll(withdrawn.map { it.key }).filter { it.state == Reminder.State.WITHDRAWN }.map { it.key }
            )
        }
        return withdrawn.size
    }

    /** Давнее забывается: решает сама сущность, запрос только сужает отбор — и решает, и удаляет одна транзакция. */
    private suspend fun forgetThePast(now: java.time.Instant) = transactions.run {
        val stale = reminders.stale(now.minus(Reminder.RETENTION))
        reminders.deleteAll(stale.filter { it.forgettable(now) }.map { it.key })
    }

    /** Чем кончился проход: сколько сказано, погашено, не смогли сказать — и когда просыпаться. */
    data class Report(val shown: Int, val dismissed: Int, val blocked: Int, val nextAt: Instant?)

    /** Сколько проходов было, чем кончился последний и когда следующий по сроку. */
    data class State(
        val passes: Int = 0,
        val lastReport: Report? = null,
        val lastFailure: String? = null,
        /** Показать нечем: обязательства ждут листа приёмов при запуске (PLAN H3 №29). */
        val pushBlocked: Boolean = false
    )

    companion object {
        /** Дольше напоминание не ждёт: пара секунд — и показ с тем, что есть (PLAN D8). */
        val REFRESH_WAIT: Duration = Duration.ofSeconds(2)

        /** Сбой прохода — тоже срок: не позже этого мы возвращаемся (довод `QueueOutbox`). */
        val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(1)
    }
}
