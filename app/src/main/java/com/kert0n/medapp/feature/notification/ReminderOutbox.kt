package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.notification.Delivery
import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.queue.OutboxLoop
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Единственный владелец показа и будильника** (PLAN D8). Обязательство уже лежит в таблице;
 * забирает его не тот, кто положил, а тот, кто следит за таблицей: сигнал
 * [ReminderRecords.changes] приходит после коммита по определению, и гонки «разбудили до
 * фиксации» нет (F5). Поэтому сценарий, изменивший календарь, ничего не зовёт после транзакции —
 * правило, которое нельзя было проверить, заменено механизмом.
 *
 * Проход идёт и при старте процесса — в таблице могло остаться с прошлого запуска. Границы здесь
 * же: **никто, кроме этого класса, не зовёт `Notifier` и `ReminderAlarms`** ([NotificationOwnershipTest]);
 * что должно быть обещано, решает [NotificationReconciliation] и в показ не лезет.
 */
@Singleton
class ReminderOutbox @Inject constructor(
    private val reminders: ReminderRecords,
    private val notifier: Notifier,
    private val subjects: ReminderSubjects,
    private val alarms: ReminderAlarms,
    private val freshness: Freshness,
    private val transactions: com.kert0n.medapp.queue.Transactions,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** Проход один за раз: цикл и приёмник будильника не показывают одно и то же дважды. */
    private val passes = Mutex()

    private val _state = MutableStateFlow(State())

    /** Что происходит с доставкой — для экрана состояния (PLAN H3 №28). */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Цикл владельца (PLAN C1 «Цикл владельца — один»): сигнал таблицы после коммита → проход →
     * срок следующего. Срок — ближайшая постановка: в процессе таймер приходит сам, а из мёртвого
     * процесса будит будильник, который проход ставит тот же.
     */
    private val loop = OutboxLoop(reminders.changes(), RETRY_AFTER_FAILURE, clock, scope) { pass().nextAt }

    /** Наблюдатель таблицы встал — для проверок, которые ждут именно сигнала. */
    val ready: StateFlow<Boolean> get() = loop.ready

    /** Один раз на процесс: повторный вызов ничего не делает. */
    fun start() = loop.start()

    /** Повод без ожидания: сработал будильник, вошли в приложение, загрузилось устройство. */
    fun runNow() = loop.runNow()

    /**
     * Один проход: погасить отозванное, сказать наступившее, прибрать давнее и переставить
     * будильник. Перед напоминанием о приёме — короткая сверка с сервером, чтобы остаток на
     * карточке был свежим, насколько успели (PLAN D8, E4).
     *
     * Под замком: два входа — цикл и приёмник будильника — не должны идти одновременно, иначе один
     * покажет то, что другой уже пометил сказанным. Сбой прохода не бросает наружу: он записан,
     * а возврат назначен — будильником, чтобы пережить смерть процесса, и сроком в отчёте.
     */
    suspend fun pass(): Report = passes.withLock {
        try {
            attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Сбой прохода — тоже срок: мы вернёмся, а не замолчим до входа в приложение (E4).
            _state.update { it.copy(passes = it.passes + 1, lastFailure = failure.toString()) }
            // Переставляется только приблизительная: точная остаётся той, что была, — срок приёма
            // сбой прохода не двигает.
            val retryAt = clock.instant().plus(RETRY_AFTER_FAILURE)
            attempt { alarms.wakeAt(retryAt, exact = false) }
            Report(shown = 0, dismissed = 0, blocked = 0, nextAt = retryAt)
        }
    }

    private suspend fun attempt(): Report {
        var dismissed = dismissGroundless()
        // Ожидание свежести — окно до двух секунд, и прочитанное до него устаревает: человек
        // успевает отложить, лечение — отмениться, а срок — наступить. Показывается то, что
        // наступило **после** него, и «сейчас» берётся после него же.
        if (due(clock.instant()).any { it.kind == NotificationKind.INTAKE_DUE }) freshenBriefly()
        val now = clock.instant()
        val due = due(now)

        var shown = 0
        var blocked = 0
        for (reminder in due) {
            // Сбой одного показа не уносит остальные: работник очереди изолирует свои так же (E4).
            val outcome = attempt { notifier.show(reminder, subjects.of(reminder.target)) }.getOrElse { Delivery.FAILED }
            if (outcome == Delivery.NOT_ALLOWED) {
                // Показать нечем: обязательство ждёт листа приёмов, и будильника оно не попросит.
                blocked++
                continue
            }
            when (settle(reminder.key, outcome)) {
                Settled.RECORDED -> if (outcome == Delivery.SHOWN) shown++
                // Пока система показывала, обязательство изменилось: карточка висит без основания.
                // Гасится сейчас; сорвалось — карточка записана за обязательством (`noticedAt`), и
                // следующий проход погасит её первым шагом (C1).
                Settled.OUTDATED -> if (outcome == Delivery.SHOWN) {
                    attempt { notifier.dismiss(reminder.key) }
                    dismissed++
                }
            }
        }
        forgetThePast(now)

        // Постановки ставятся по **свежему** чтению: за проход обязательства изменились. Их две —
        // точная и приблизительная, каждая на ближайший срок своей точности: приблизительную
        // система вправе задержать, и точный приём за ней не ждёт (D8).
        val left = reminders.awaiting(NoticeDelivery.SYSTEM)
        val at = clock.instant()
        val nextExact = left.filter { it.exact }.mapNotNull { it.wakeAt(at, clock.zone) }.minOrNull()
        val nextInexact = left.filterNot { it.exact }.mapNotNull { it.wakeAt(at, clock.zone) }.minOrNull()
        keep(nextExact, exact = true)
        keep(nextInexact, exact = false)
        val next = listOfNotNull(nextExact, nextInexact).minOrNull()
        val report = Report(shown = shown, dismissed = dismissed, blocked = blocked, nextAt = next)
        _state.update { it.copy(passes = it.passes + 1, lastReport = report, lastFailure = null, pushBlocked = blocked > 0) }
        return report
    }

    /**
     * Свежесть перед напоминанием — насколько успели. Заход, которого ждали, могли отменить: его
     * позвал первым экран, и человек с него ушёл. Для прохода это то же «не успели», и напоминание
     * говорится с тем, что есть. Своя отмена летит дальше: `ensureActive` бросает её.
     */
    private suspend fun freshenBriefly() {
        try {
            freshness.refreshBriefly(REFRESH_WAIT)
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * Экран показал баннеры дня (PLAN D8): владелец доставки один и для системы, и для экрана —
     * отметка показа делается здесь, перечитав обязательство своей транзакцией. Отмечается только
     * баннер, и только наступивший: системное обязательство экрану не принадлежит.
     */
    suspend fun bannerShown(keys: Collection<NotificationKey>) {
        if (keys.isEmpty()) return
        transactions.run {
            val at = clock.instant()
            val shown = reminders.findAll(keys)
                .filter { it.delivery == NoticeDelivery.IN_APP_BANNER && it.isDue(at) }
                .onEach { it.deliveredAt(at) }
            reminders.saveAll(shown)
        }
    }

    /**
     * Что сказать сейчас — свежим чтением: наступившее, несказанное и не пережившее свой день
     * ([Reminder.speaksAt]). Вчерашнее остаётся ждать полку дня (PLAN C1).
     */
    private suspend fun due(now: Instant): List<Reminder> =
        reminders.awaiting(NoticeDelivery.SYSTEM).filter { it.speaksAt(now, clock.zone) }

    /**
     * Записать исход показа — **перечитав** обязательство своей транзакцией. Пока шла система,
     * лечение могли отменить, человек мог отложить. Писать прочитанное значило бы воскресить снятое
     * и потерять отсрочку (F5, C1).
     *
     * Изменилось — решение приняли без нас, и оно свежее: исход не записывается, а вызывающий
     * гасит показ, оставшийся без основания. «Следующий проход поправит» здесь не работает: он
     * снимает отозванное, а отложенное остаётся обещанным на новый срок — с карточкой в шторке.
     */
    private suspend fun settle(key: NotificationKey, outcome: Delivery): Settled =
        transactions.run {
            val fresh = reminders.find(key) ?: return@run Settled.OUTDATED
            val at = clock.instant()
            if (!fresh.isDue(at)) {
                // Сказали о том, чего уже нет: карточка есть — обязательство это запомнит, чтобы
                // гашение, сорвавшееся сейчас, повторил следующий проход.
                if (outcome == Delivery.SHOWN) {
                    fresh.noticedAt(at)
                    reminders.saveAll(listOf(fresh))
                }
                return@run Settled.OUTDATED
            }
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

    /** Постановка названной точности — на срок, если он есть; иначе снимается. */
    private suspend fun keep(at: Instant?, exact: Boolean) {
        if (at == null) alarms.stopWaking(exact) else alarms.wakeAt(at, exact)
    }

    /**
     * Карточки без основания гасятся первым шагом — все, о ком говорили и кто сейчас не
     * сказанное-и-наступившее: отозванное и отложенное после показа ([Reminder.cardIsUp]).
     * Гашение идемпотентно, и сорвавшееся повторится следующим проходом само — память о карточке
     * у обязательства, а не у прохода. Система не откатывается вместе с базой (F5), поэтому
     * гашение идёт по прочитанному, а удаление отозванного — **перечитав** в своей транзакции:
     * пока система гасила, повод мог вернуться, и `promise` воскресил обязательство под тем же
     * ключом. Удалить его значило бы потерять живое; гашение воскрешённого безвредно.
     */
    private suspend fun dismissGroundless(): Int {
        val groundless = reminders.groundless().filter { it.isWithdrawn || it.cardIsUp }
        for (reminder in groundless) notifier.dismiss(reminder.key)
        val withdrawn = groundless.filter { it.isWithdrawn }.map { it.key }
        if (withdrawn.isNotEmpty()) {
            transactions.run {
                reminders.deleteAll(reminders.findAll(withdrawn).filter { it.isWithdrawn }.map { it.key })
            }
        }
        return groundless.size
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
