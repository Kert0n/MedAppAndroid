package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
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
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Что должно быть сказано человеку — сборка по всем агрегатам (PLAN D8): курс отвечает за свои
 * пункты, коробка — за срок, обеспечение — за нехватку, а «спросить каждого и сложить» — работа
 * сценария (H1). Здесь только вычисление: показ и журнал — у [NotificationDelivery].
 */
class NotificationPlanning @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository,
    private val courses: CourseStorageRepository,
    private val settings: NotificationSettingsSource
) {

    /**
     * Обеспечение идущих лечений на момент [at] (PLAN D8): сокращение — событием, сразу и один раз
     * на событие; за `coverageThresholdDays` календарных дней до первого необеспеченного пункта и в
     * его день. День берётся **в зоне курса** — той же, в которой стоит и пункт: день устройства
     * у полуночи может быть уже другим. Обеспеченному курсу предупреждать нечего.
     */
    suspend fun coverageDue(at: Instant): List<Reminder> {
        val threshold = settings.current().coverageThresholdDays
        val due = mutableListOf<Reminder>()
        for ((courseId, coverage) in courses.observeCoverages().first()) {
            val zone = courses.findPlan(courseId)?.schedule?.zone ?: continue
            val today = at.atZone(zone).toLocalDate()
            for (reduction in courses.observeReductions(courseId).first()) {
                due += Reminder(NotificationKey.reduction(reduction.id), NotificationTarget.CourseSources(courseId), reduction.at)
            }
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
     * Напоминания о приёмах на ближайшие [REMINDER_HORIZON]: будильники ставятся на них, а не на
     * всё 60-дневное окно календаря (C1). Выключенные напоминания — пустой список.
     */
    suspend fun remindersDue(now: Instant): List<Reminder> {
        if (!settings.current().intakeRemindersEnabled) return emptyList()
        return intakes.plannedBefore(now.plus(REMINDER_HORIZON))
            .filter { !it.plannedAt.isBefore(now.minus(GRACE)) }
            .map { reminder(it) }
    }

    /** Пункты, ставшие пропуском неответом (их называет проход календаря), — уведомлением каждому (PLAN D8). */
    fun missed(intakeIds: List<Uuid>, at: Instant): List<Reminder> = intakeIds.map { id ->
        Reminder(NotificationKey.intake(id, NotificationKind.INTAKE_MISSED), NotificationTarget.Intake(id), at)
    }

    /**
     * Сводка дня — одно уведомление, если есть о чём: события дня ([events]) или плановые пункты на
     * сегодня. Пусто — сводки нет; выключена — тоже.
     */
    suspend fun digest(today: LocalDate, zone: ZoneId, events: Int, at: Instant): Reminder? {
        if (!settings.current().digestEnabled) return null
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val plannedToday = intakes.plannedBefore(dayEnd).any { it.slot.localDate == today }
        if (events == 0 && !plannedToday) return null
        return Reminder(NotificationKey.digest(today), NotificationTarget.DayPlan(today), at)
    }

    /** Напоминание об одном пункте — когда сработал его будильник; пункт уже отвечен — `null`. */
    suspend fun reminderFor(intakeId: Uuid): Reminder? {
        if (!settings.current().intakeRemindersEnabled) return null
        val intake = intakes.find(intakeId) as? CourseIntake ?: return null
        if (intake.status != IntakeStatus.PLANNED) return null
        return reminder(intake)
    }

    private fun reminder(intake: CourseIntake) = Reminder(
        key = NotificationKey.intake(intake.id, NotificationKind.INTAKE_DUE),
        target = NotificationTarget.Intake(intake.id),
        dueAt = intake.plannedAt
    )

    companion object {
        /** На сколько вперёд стоят будильники; перестраиваются ежедневным проходом, входом и загрузкой (C1). */
        val REMINDER_HORIZON: Duration = Duration.ofHours(36)

        /** Пункт, начавшийся только что, ещё напоминает; вчерашний неотвеченный — уже нет: о нём скажет сводка. */
        val GRACE: Duration = Duration.ofMinutes(30)
    }
}
