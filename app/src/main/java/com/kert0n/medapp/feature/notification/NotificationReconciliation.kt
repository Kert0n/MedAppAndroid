package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.course.CourseRecords
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.operation.OperationReadings
import com.kert0n.medapp.feature.packages.PackageQuery
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.queue.Transactions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first

/**
 * **Что должно быть обещано сейчас** — и только это (PLAN D8): недостающее заводится, лишнее
 * отзывается. Курс отвечает за свои пункты, коробка — за срок, обеспечение — за нехватку, а
 * «спросить каждого и сложить» — работа сценария (H1).
 *
 * Сверяется то, что следует из **состояния**: сроки годности, предупреждения о нехватке, сводка,
 * внимание к очереди и напоминания о плановых пунктах. Их нельзя копить — исправленный срок
 * отменяет прежнее предупреждение, восстановленное обеспечение снимает своё, отвеченный пункт
 * снимает напоминание. Обязательства от **события** — пропуск, сокращение — сверка не отзывает:
 * повод уже в прошлом, и пересчитать его из нынешнего состояния нечем. Выключенная настройка
 * снимает обещанное своего вида и не даёт обещать новое, включённая возвращает несказанное (D8).
 *
 * Показывать и будить сверка не умеет: это дело [ReminderOutbox], и он проснётся сам. Когда
 * сверять, решают другие: [NotificationUpkeep] — по сигналу изменившихся оснований, [DailyRound] —
 * по смене дня, `SettingsChanging` — по решению человека; никто, кроме них, её не зовёт
 * (`NotificationOwnershipTest`).
 */
class NotificationReconciliation @Inject constructor(
    private val intakes: IntakeRecords,
    private val packages: PackageReadings,
    private val courses: CourseRecords,
    private val reminders: ReminderRecords,
    private val operations: OperationReadings,
    private val promising: ReminderPromising,
    private val withdrawal: ReminderWithdrawal,
    private val settings: NotificationSettingsSource,
    private val transactions: Transactions
) {

    /**
     * Привести обещанное в соответствие с тем, что есть.
     *
     * Основания читаются и обязательства пишутся **одной транзакцией** (F5): решение, принятое по
     * прочитанному до неё, легло бы поверх ответа человека, данного пока мы считали, — так сверка
     * воскрешала напоминание о только что принятом пункте. Ответ, пришедший во время сверки, ждёт
     * её замка и снимает напоминание сам. Настройки — не база: читаются до транзакции, а их
     * смена зовёт сверку отдельно.
     *
     * Без изменений сверка **не пишет ничего**: сигнал изменившейся таблицы будит владельца
     * доставки, и лишняя запись давала бы лишний проход на каждую укладку снимка.
     */
    suspend fun reconcile(now: Instant, zone: ZoneId): Report {
        val current = settings.current()
        return transactions.run {
            val today = now.atZone(zone).toLocalDate()
            val events = expiryDue(today, now, current) + coverageDue(now, current)
            val digest = digest(today, zone, events.size, current)
            val desired = events + listOfNotNull(digest, syncAttention(now))
            val reductions = if (current.remoteChangeEnabled) reductionsDue(now) else emptyList()
            // Напоминания о приёме и сообщения о чужом сокращении сверка держит в **обе** стороны:
            // включены — обещаем плановые, выключены — снимаем обещанное; пункт, переставший быть
            // плановым, снимается и при включённых. Обещает пункты и календарь, когда заводит их,
            // чтобы не ждать прохода; `promise` идемпотентен, и два обещающих не спорят.
            val intakeDue = if (current.intakeRemindersEnabled) plannedReminders() else emptyList()
            // Лишнее — то, что было обещано по состоянию, а в нынешнем состоянии повода не имеет.
            // Сводка, обещанная на другое время и ещё не сказанная, — тоже лишнее: человек перенёс её,
            // и снятое здесь обещание ниже воскреснет с новым сроком.
            val wanted = (desired + intakeDue).mapTo(HashSet()) { it.key }
            val stale = reminders.ofKinds(FROM_STATE)
                .filter { it.key !in wanted || (digest != null && it.key == digest.key && it.isDue && it.dueAt != digest.dueAt) }
                .map { it.key }
            val silenced = if (current.remoteChangeEnabled) emptyList() else reminders.ofKinds(listOf(NotificationKind.COVERAGE_SHORT)).map { it.key }
            // Сначала снять, потом обещать: воскрешение снятого даёт ему новый срок.
            withdrawal.withdrawKeys(stale + silenced)
            promising.promise(desired + reductions + intakeDue)
            Report(promised = desired.size + intakeDue.size, withdrawn = stale.size + silenced.size)
        }
    }

    /**
     * Обещания на плановые пункты — по календарю. Сверка их не выдумывает: срок обязательства и
     * есть момент пункта.
     */
    private suspend fun plannedReminders(): List<Reminder> =
        intakes.plannedBefore(EVERY_PLANNED).map {
            Reminder(
                NotificationKey.intake(it.id, NotificationKind.INTAKE_DUE),
                NotificationTarget.Intake(it.id),
                it.plannedAt
            )
        }

    /** Сколько обещано по нынешнему состоянию и сколько снято как потерявшее повод. */
    data class Report(val promised: Int, val withdrawn: Int)

    /**
     * Сокращения обеспечения за срок хранения (PLAN D8): обязательство заводится один раз на
     * событие, и повторная сверка его не трогает. Старше срока — не воскресает: сказать о
     * прошлогоднем событии нечего, а строки о нём владелец доставки давно прибрал.
     */
    private suspend fun reductionsDue(at: Instant): List<Reminder> {
        val inProgress = courses.planIds().toSet()
        return courses.recentReductions(at.minus(Reminder.RETENTION))
            .filter { it.courseId in inProgress }
            .map { reduction -> Reminder(NotificationKey.reduction(reduction.id), NotificationTarget.CourseSources(reduction.courseId), reduction.at) }
    }

    /**
     * Обеспечение идущих лечений на момент [at] (PLAN D8): за `coverageThresholdDays` календарных
     * дней до первого необеспеченного пункта и в его день. Сокращение — событие, и живёт оно
     * отдельно ([reductionsDue]). День считает само обеспечение — в зоне курса, которую несёт.
     * Обеспеченному курсу предупреждать нечего.
     */
    suspend fun coverageDue(at: Instant, current: NotificationSettings? = null): List<Reminder> {
        val threshold = (current ?: settings.current()).coverageThresholdDays
        val due = mutableListOf<Reminder>()
        for ((courseId, coverage) in courses.observeCoverages().first()) {
            val firstUncoveredAt = coverage.firstUncoveredAt ?: continue
            val kind = when (coverage.noticeOn(at, threshold)) {
                CourseCoverage.Notice.AHEAD -> NotificationKind.COVERAGE_3D
                CourseCoverage.Notice.END -> NotificationKind.COVERAGE_END
                null -> continue
            }
            due += Reminder(NotificationKey.coverage(courseId, firstUncoveredAt, kind), NotificationTarget.CourseSources(courseId), at)
        }
        return due
    }

    /**
     * Годность на день [today] (PLAN D8): коробкам, из которых курс берёт (назначены идущему курсу
     * с выделением больше нуля), — за три дня и за день системным уведомлением; всем живым — в
     * последний день баннером в приложении. Этап — только сегодняшний: поздно подключённая коробка
     * залпа прошедших не получает; просроченной этапов нет.
     */
    suspend fun expiryDue(today: LocalDate, at: Instant, current: NotificationSettings? = null): List<Reminder> {
        val sourcesEnabled = (current ?: settings.current()).expirySourceRemindersEnabled
        return packages.list(PackageQuery(), today).first().mapNotNull { pkg ->
            val expiresOn = pkg.facts.expiresOn ?: return@mapNotNull null
            val isSource = pkg.isCourseSource
            val kind = when (expiresOn.stageOn(today)) {
                ExpiryDate.Stage.SOURCE_3D -> NotificationKind.EXPIRY_SOURCE_3D.takeIf { isSource && sourcesEnabled }
                ExpiryDate.Stage.SOURCE_1D -> NotificationKind.EXPIRY_SOURCE_1D.takeIf { isSource && sourcesEnabled }
                ExpiryDate.Stage.TODAY -> NotificationKind.EXPIRY_TODAY
                null -> null
            } ?: return@mapNotNull null
            Reminder(
                key = NotificationKey.expiry(pkg.id, expiresOn, kind),
                target = NotificationTarget.PackageCard(pkg.id),
                dueAt = at
            )
        }
    }

    /**
     * Очередь ждёт решения человека (PLAN D8, H3 №28): отвергнутое сервером или нечитаемое само не
     * разрешится. Строка, которой не хватило словаря, — не повод: её дочитает работник. Обязательство
     * одно на всю очередь, предмет — последняя такая операция: новый отказ говорится снова, прежняя
     * карточка уходит, а сказанное второй раз не беспокоит. Решать стало нечего — снимается.
     * Отвергнутое остаётся в очереди, пока человек его не разберёт (`OperationDismissing`), и через
     * срок хранения сказанное забывается — тогда о нерешённом напоминают ещё раз.
     */
    suspend fun syncAttention(at: Instant): Reminder? {
        val newest = operations.observeOutstanding().first().lastOrNull { it.needsDecision } ?: return null
        return Reminder(NotificationKey.sync(newest.id), NotificationTarget.SyncStatus, at)
    }

    /** Пункты, ставшие пропуском неответом (их называет проход календаря), — уведомлением каждому (PLAN D8). */
    fun missed(intakeIds: List<Uuid>, at: Instant): List<Reminder> = intakeIds.map { id ->
        Reminder(NotificationKey.intake(id, NotificationKind.INTAKE_MISSED), NotificationTarget.Intake(id), at)
    }

    /**
     * Сводка дня — одно обещание, если есть о чём: события дня ([events]) или плановые пункты на
     * сегодня. Пусто — сводки нет; выключена — тоже. Срок — желаемое время `digestAt`. Это
     * уведомление-ссылка на экран плана на дату: содержания в шторке нет.
     */
    suspend fun digest(today: LocalDate, zone: ZoneId, events: Int, current: NotificationSettings? = null): Reminder? {
        val settings = current ?: settings.current()
        if (!settings.digestEnabled) return null
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        // «Сегодня» у сводки — день устройства, и пункты в него попадают своим **моментом**: у
        // курса свой день, и сравнивать одно с другим нельзя (C1).
        val plannedToday = intakes.plannedBefore(dayEnd).any { !it.plannedAt.isBefore(dayStart) }
        if (events == 0 && !plannedToday) return null
        // Срок сводки — желаемое время, а не миг прохода: проход зовут и вход, и загрузка, и
        // раньше своего часа сводка не наступает (PLAN D8).
        return Reminder(NotificationKey.digest(today), NotificationTarget.DayPlan(today), today.atTime(settings.digestAt).atZone(zone).toInstant())
    }

    companion object {
        /**
         * Граница «все плановые»: окном пунктов ведает календарь, и сверке незачем заводить своё.
         * `Instant.MAX` здесь не годится — база держит момент числом миллисекунд.
         */
        private val EVERY_PLANNED: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)

        /** Что следует из состояния и потому сверяется: повод исчез — обещание снимается. */
        private val FROM_STATE = listOf(
            NotificationKind.EXPIRY_SOURCE_3D,
            NotificationKind.EXPIRY_SOURCE_1D,
            NotificationKind.EXPIRY_TODAY,
            NotificationKind.COVERAGE_3D,
            NotificationKind.COVERAGE_END,
            NotificationKind.DAILY_DIGEST,
            NotificationKind.SYNC_ATTENTION,
            NotificationKind.INTAKE_DUE
        )
    }
}
