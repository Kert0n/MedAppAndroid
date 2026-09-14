package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.queue.Transactions
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * **Что должно быть обещано сейчас** — и только это (PLAN D8): недостающее заводится, лишнее
 * отзывается. Курс отвечает за свои пункты, коробка — за срок, обеспечение — за нехватку, а
 * «спросить каждого и сложить» — работа сценария (H1).
 *
 * Сверяется то, что следует из **состояния**: сроки годности, предупреждения о нехватке и сводка.
 * Их нельзя копить — исправленный срок отменяет прежнее предупреждение, восстановленное
 * обеспечение снимает своё. Обязательства от **события** — приём, пропуск, сокращение — сверка не
 * отзывает: повод уже в прошлом, и пересчитать его из нынешнего состояния нечем.
 *
 * Показывать и будить сверка не умеет: это дело [ReminderOutbox], и он проснётся сам.
 */
class NotificationReconciliation @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository,
    private val courses: CourseStorageRepository,
    private val reminders: ReminderStorageRepository,
    private val promising: ReminderPromising,
    private val withdrawal: ReminderWithdrawal,
    private val settings: NotificationSettingsSource,
    private val transactions: Transactions
) {

    /**
     * Привести обещанное в соответствие с тем, что есть. Одной транзакцией: полусверенное
     * состояние не должно пережить падение.
     */
    suspend fun reconcile(now: Instant, zone: ZoneId): Report = transactions.run {
        val today = now.atZone(zone).toLocalDate()
        val events = expiryDue(today, now) + coverageDue(now)
        val desired = events + listOfNotNull(digest(today, zone, events.size))
        promising.promise(desired + reductionsDue(now))
        // Лишнее — то, что было обещано по состоянию, а в нынешнем состоянии повода не имеет.
        val wanted = desired.mapTo(HashSet()) { it.key }
        val stale = reminders.ofKinds(FROM_STATE).map { it.key }.filterNot { it in wanted }
        // Выключенные напоминания снимают и уже обещанное: человек попросил молчать (B18).
        val silenced = if (settings.current().intakeRemindersEnabled) {
            emptyList()
        } else {
            reminders.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { it.key }
        }
        withdrawal.withdrawKeys(stale + silenced)
        Report(promised = desired.size, withdrawn = stale.size + silenced.size)
    }

    /** Сколько обещано по нынешнему состоянию и сколько снято как потерявшее повод. */
    data class Report(val promised: Int, val withdrawn: Int)

    /**
     * Сокращения обеспечения за срок хранения (PLAN D8): обязательство заводится один раз на
     * событие, и повторная сверка его не трогает. Старше срока — не воскресает: сказать о
     * прошлогоднем событии нечего, а строки о нём владелец доставки давно прибрал.
     */
    private suspend fun reductionsDue(at: Instant): List<Reminder> =
        courses.observeCoverages().first().keys.flatMap { courseId ->
            courses.reductionsSince(courseId, at.minus(Reminder.RETENTION)).map { reduction ->
                Reminder(NotificationKey.reduction(reduction.id), NotificationTarget.CourseSources(courseId), reduction.at)
            }
        }

    /**
     * Обеспечение идущих лечений на момент [at] (PLAN D8): за `coverageThresholdDays` календарных
     * дней до первого необеспеченного пункта и в его день. Сокращение — событие, и живёт оно
     * отдельно ([reductionsDue]). День берётся **в зоне курса** — той же, в которой стоит и пункт: день устройства
     * у полуночи может быть уже другим. Обеспеченному курсу предупреждать нечего.
     */
    suspend fun coverageDue(at: Instant): List<Reminder> {
        val threshold = settings.current().coverageThresholdDays
        val due = mutableListOf<Reminder>()
        for ((courseId, coverage) in courses.observeCoverages().first()) {
            val zone = courses.findPlan(courseId)?.schedule?.zone ?: continue
            val today = at.atZone(zone).toLocalDate()
            val firstUncoveredAt = coverage.firstUncoveredAt ?: continue
            val kind = when (coverage.noticeOn(today, zone, threshold)) {
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
    suspend fun expiryDue(today: LocalDate, at: Instant): List<Reminder> {
        val sourcesEnabled = settings.current().expirySourceRemindersEnabled
        return packages.list(PackageQuery(), today).first().mapNotNull { pkg ->
            val expiresOn = pkg.facts.expiresOn ?: return@mapNotNull null
            val isSource = pkg.holdingCourseId != null && !pkg.availability.myAllocation.isZero
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

    /** Пункты, ставшие пропуском неответом (их называет проход календаря), — уведомлением каждому (PLAN D8). */
    fun missed(intakeIds: List<Uuid>, at: Instant): List<Reminder> = intakeIds.map { id ->
        Reminder(NotificationKey.intake(id, NotificationKind.INTAKE_MISSED), NotificationTarget.Intake(id), at)
    }

    /**
     * Сводка дня — одно обещание, если есть о чём: события дня ([events]) или плановые пункты на
     * сегодня. Пусто — сводки нет; выключена — тоже. Срок — желаемое время `digestAt`.
     */
    suspend fun digest(today: LocalDate, zone: ZoneId, events: Int): Reminder? {
        val settings = settings.current()
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
        /** Что следует из состояния и потому сверяется: повод исчез — обещание снимается. */
        private val FROM_STATE = listOf(
            NotificationKind.EXPIRY_SOURCE_3D,
            NotificationKind.EXPIRY_SOURCE_1D,
            NotificationKind.EXPIRY_TODAY,
            NotificationKind.COVERAGE_3D,
            NotificationKind.COVERAGE_END,
            NotificationKind.DAILY_DIGEST
        )
    }
}
