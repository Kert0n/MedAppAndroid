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
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.storage.server.SyncOperationStorageRepository
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
 * отзывает: повод уже в прошлом, и пересчитать его из нынешнего состояния нечем. Исключение одно —
 * человек попросил молчать: выключенная настройка снимает обещанное своего вида и не даёт обещать
 * новое, включённая возвращает несказанное (D8).
 *
 * Показывать и будить сверка не умеет: это дело [ReminderOutbox], и он проснётся сам.
 */
class NotificationReconciliation @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository,
    private val courses: CourseStorageRepository,
    private val reminders: ReminderStorageRepository,
    private val operations: SyncOperationStorageRepository,
    private val promising: ReminderPromising,
    private val withdrawal: ReminderWithdrawal,
    private val settings: NotificationSettingsSource,
    private val transactions: Transactions
) {

    /**
     * Привести обещанное в соответствие с тем, что есть.
     *
     * Считается **до** транзакции, а пишется в ней. Чтения здесь долгие — весь список пачек с
     * проекциями, обеспечение каждого идущего лечения, его сокращения, — и держать под ними пишущую
     * транзакцию значило бы запирать базу для очереди отправки и сценариев человека на всё это
     * время. Запись же идёт одной: полусверенное состояние не должно пережить падение.
     *
     * Считанное могло устареть, пока мы читали, — и это не беда: обещание заводится только
     * недостающее, снимается только беспричинное, а следующий проход поправит.
     */
    suspend fun reconcile(now: Instant, zone: ZoneId): Report {
        val today = now.atZone(zone).toLocalDate()
        val current = settings.current()
        val events = expiryDue(today, now) + coverageDue(now)
        val digest = digest(today, zone, events.size)
        val desired = events + listOfNotNull(digest, syncAttention(now))
        val reductions = if (current.remoteChangeEnabled) reductionsDue(now) else emptyList()
        // Лишнее — то, что было обещано по состоянию, а в нынешнем состоянии повода не имеет.
        // Сводка, обещанная на другое время и ещё не сказанная, — тоже лишнее: человек перенёс её,
        // и снятое здесь обещание ниже воскреснет с новым сроком.
        val wanted = desired.mapTo(HashSet()) { it.key }
        val stale = reminders.ofKinds(FROM_STATE)
            .filter { it.key !in wanted || (digest != null && it.key == digest.key && it.state == Reminder.State.DUE && it.dueAt != digest.dueAt) }
            .map { it.key }
        // Напоминания о приёме и сообщения о чужом сокращении сверка держит в **обе** стороны:
        // включены — обещаем, выключены — снимаем обещанное. Обещает приёмы и календарь, когда
        // заводит пункт, чтобы не ждать прохода; `promise` идемпотентен, и два обещающих не спорят.
        // Без обратного хода выключить и включить напоминания значило бы потерять их навсегда (C1).
        val intakeDue = if (current.intakeRemindersEnabled) plannedReminders() else emptyList()
        val silenced = buildList {
            if (!current.intakeRemindersEnabled) addAll(reminders.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { it.key })
            if (!current.remoteChangeEnabled) addAll(reminders.ofKinds(listOf(NotificationKind.COVERAGE_SHORT)).map { it.key })
        }

        transactions.run {
            // Сначала снять, потом обещать: воскрешение снятого даёт ему новый срок.
            withdrawal.withdrawKeys(stale + silenced)
            promising.promise(desired + reductions + intakeDue)
        }
        return Report(promised = desired.size + intakeDue.size, withdrawn = stale.size + silenced.size)
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

    /**
     * Очередь ждёт решения человека (PLAN D8, H3 №28): отвергнутое сервером или нечитаемое само не
     * разрешится. Обязательство одно на всю очередь, предмет — последняя такая операция: новый
     * отказ говорится снова, прежняя карточка уходит, а сказанное второй раз не беспокоит. Решать
     * стало нечего — снимается. Отвергнутое остаётся в очереди, пока человек его не разберёт, и
     * через срок хранения сказанное забывается — тогда о нерешённом напоминают ещё раз.
     */
    suspend fun syncAttention(at: Instant): Reminder? {
        val newest = operations.observeOutstanding().first().lastOrNull {
            it is StoredSyncOperation.Unreadable ||
                (it is StoredSyncOperation.Readable && it.operation.status == SyncOperationStatus.REFUSED)
        } ?: return null
        return Reminder(NotificationKey.sync(newest.id), NotificationTarget.SyncStatus, at)
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
            NotificationKind.SYNC_ATTENTION
        )
    }
}
