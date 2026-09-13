package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Плановые пункты лечения — окном, а не на весь курс вперёд (PLAN F4). Сколько доз осталось и
 * когда они наступают, отвечает курс (`remainingOccurrences`); из какой пачки каждая возьмётся —
 * тоже он (`spendOrder`). Разложить это по строкам приёмов — работа сценария: какие пункты уже
 * отвечены и какие лежат в базе, знает он, а не курс.
 *
 * Шаг внутри уже открытой транзакции того, кто лечение завёл, поправил или поддерживает, — как
 * `CourseClosing`. Повтор идемпотентен: пункт узнают по курсу, дате и времени, и второй такой же не
 * заводится.
 */
class CourseCalendar @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository
) {

    /**
     * Окно достроено по нынешнему плану: недостающие пункты до `now + WINDOW` заведены, а лишние
     * плановые — которых по прогрессу больше нет (сократили число доз, поздний ответ сдвинул конец
     * назад) — убраны. Отвеченные пункты не трогаются никогда. Возвращает число заведённых.
     */
    suspend fun extend(course: Course, now: Instant): Int {
        val existing = intakes.ofCourse(course.id).filterIsInstance<CourseIntake>()
        val progress = progressOf(existing)
        val remaining = course.remainingOccurrences(progress)
        intakes.prunePlanned(course.id, remaining.toSet())
        val window = remaining.filter { it.at.isBefore(now.plus(WINDOW)) }
        if (window.isEmpty()) return 0
        val order = course.spendOrder(Doses(window.size), availabilityOf(course))
        val planned = window.mapIndexed { index, slot ->
            CourseIntake(
                id = Uuid.random(),
                courseId = course.id,
                courseRevision = course.revision,
                slot = slot,
                plannedAmount = course.dose,
                plannedPackage = order[index]
            )
        }
        return intakes.materialise(planned)
    }

    /**
     * Неотвеченные пункты, чей день **в зоне курса** кончился, — `MISSED` условным переходом из
     * `PLANNED`: ответ, пришедший тем временем, не перетирается. Моментом ответа служит конец дня
     * пункта — тогда неответ и наступил (PLAN D6). Возвращает число пропущенных.
     */
    suspend fun missOverdue(course: Course, now: Instant): Int {
        val zone = course.schedule.zone
        val today = now.atZone(zone).toLocalDate()
        val overdue = intakes.ofCourse(course.id).filterIsInstance<CourseIntake>()
            .filter { it.status == IntakeStatus.PLANNED && it.slot.localDate.isBefore(today) }
        return overdue.count { intake ->
            val endOfDay = intake.slot.localDate.plusDays(1).atStartOfDay(zone).toInstant()
            intakes.record(IntakeOutcome(intake.miss(endOfDay), expected = setOf(IntakeStatus.PLANNED), recordedAt = now))
        }
    }

    /**
     * Будущее перестроено заново: план изменился — дозой, расписанием или пачками, — и прежние
     * плановые пункты несут прежнюю дозу и прежнюю пачку. Они не факты и уходят; отвеченные
     * остаются как были (PLAN D5).
     */
    suspend fun replan(course: Course, now: Instant): Int {
        intakes.prunePlanned(course.id, keep = emptySet())
        return extend(course, now)
    }

    /**
     * Расклад «сколько доступно мне» по пачкам курса — от подтверждённого остатка, как считает
     * приём (PLAN D4). Пачку, которой уже нет, курс вот-вот потеряет своим переходом; до тех пор
     * она не даёт ничего.
     */
    internal suspend fun availabilityOf(course: Course): Availability = Availability(
        course.sources.associate { source ->
            source.pkg.id to (
                packages.find(source.pkg.id)?.let { PackageAvailability(it, effective = it.quantity).availableToMe }
                    ?: Quantity.zero(source.pkg.unit)
                )
        }
    )

    companion object {
        /** На сколько вперёд лежат плановые пункты: достраивает их `CourseUpkeep` (PLAN F4). */
        val WINDOW: Duration = Duration.ofDays(60)

        /** Прогресс лечения — из его пунктов: принятые и пропущенные. */
        fun progressOf(intakes: List<CourseIntake>): CourseProgress = CourseProgress(
            taken = intakes.filter { it.status == IntakeStatus.TAKEN }.mapTo(HashSet()) { it.slot },
            missed = intakes.filter { it.status == IntakeStatus.MISSED }.mapTo(HashSet()) { it.slot }
        )
    }
}
