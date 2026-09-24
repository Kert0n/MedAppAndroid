package com.kert0n.medapp.domain.intake

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.QuantityUnit
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Пункт курса вместе с ответом на него. План — [slot], [plannedAmount], [plannedPackage] — ответ
 * не переписывает, переходы меняют только [answer]. [slot] вместе с [courseRevision] — тождество
 * пункта при повторной материализации (PLAN F4). [plannedPackage] — пачка, из которой пункт
 * обеспечен, `null` у необеспеченного; фактическая пачка подтверждённого лежит в [TakenDose] и
 * может быть другой пачкой курса (D6). [courseId] — тождество эпизода, а не ссылка на вещь:
 * запись эпизода вечна, и приём называет её номером, как называют его самого.
 */
class CourseIntake(
    override val id: Uuid,
    val courseId: Uuid,
    val courseRevision: Revision,
    val slot: ScheduledOccurrence,
    val plannedAmount: Dose,
    val plannedPackage: PackageRef? = null,
    val answer: IntakeAnswer? = null
) : Intake {

    init {
        val taken = taken
        require(taken == null || taken.amount.unit == unit) {
            "фактическое количество измеряется единицей приёма"
        }
    }

    /**
     * Единица НА МОМЕНТ СОБЫТИЯ, и берётся она у плановой дозы: второе поле с той же единицей
     * могло бы с ней разойтись.
     */
    override val unit: QuantityUnit get() = plannedAmount.unit

    override val status: IntakeStatus
        get() = when (answer) {
            null -> IntakeStatus.PLANNED
            is IntakeAnswer.Taken -> IntakeStatus.TAKEN
            is IntakeAnswer.Missed -> IntakeStatus.MISSED
            is IntakeAnswer.Cancelled -> IntakeStatus.CANCELLED
        }

    override val taken: TakenDose? get() = (answer as? IntakeAnswer.Taken)?.dose

    /** Когда наступает пункт. */
    val plannedAt: Instant get() = slot.at

    override fun projection(): IntakeProjection.Scheduled = IntakeProjection.Scheduled(
        id = id,
        courseId = courseId,
        courseRevision = courseRevision,
        slot = slot,
        plannedAmount = plannedAmount,
        plannedPackage = plannedPackage,
        answer = answer,
        status = status,
        taken = taken
    )

    /** Обеспечен ли пункт: источник с целой дозой под него найден (PLAN D5). */
    val isSupplied: Boolean get() = plannedPackage != null

    /**
     * Подтверждение состоявшимся фактом: фактические количество и пачка могут отличаться от
     * плана, расход равен факту (PLAN D5). Факт собирает акт [Package.take] — там и проверка, что
     * из пачки можно взять; здесь проверяется только, что факт измерен единицей этого пункта.
     * Подтверждается неотвеченный или непринятый пункт — отказ и неответ подтверждаются одинаково;
     * повторное подтверждение — второй факт со своим идентификатором, и здесь оно отвергается (E2).
     */
    fun confirm(taken: TakenDose): CourseIntake {
        check(answer == null || answer is IntakeAnswer.Missed) {
            "подтверждается неотвеченный приём, а не $status"
        }
        return answered(IntakeAnswer.Taken(taken))
    }

    /**
     * Не принято — отказался или не ответил до конца дня курса в его зоне (C1). Один переход на
     * оба случая: расхода нет, потребность не уменьшается, а доза уезжает вперёд. Повтор ничего
     * не меняет; подтвердить ещё можно.
     */
    fun miss(at: Instant): CourseIntake = respond(IntakeAnswer.Missed(at))

    /** Плановый пункт отменён вместе с курсом. Состоявшиеся приёмы этим не затрагиваются. */
    fun cancel(at: Instant): CourseIntake = respond(IntakeAnswer.Cancelled(at))

    private fun respond(to: IntakeAnswer): CourseIntake {
        if (answer != null && answer::class == to::class) return this
        check(answer == null) { "$to возможен для планового пункта, а не $status" }
        return answered(to)
    }

    /** Тот же пункт с новым ответом: план, тождество и редакция не меняются. */
    private fun answered(answer: IntakeAnswer): CourseIntake = CourseIntake(
        id = id,
        courseId = courseId,
        courseRevision = courseRevision,
        slot = slot,
        plannedAmount = plannedAmount,
        plannedPackage = plannedPackage,
        answer = answer
    )

    /** Тождество — [id]: подтверждённый приём остаётся тем же приёмом. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is CourseIntake && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CourseIntake(id=$id, status=$status, courseId=$courseId)"

    /**
     * Ответить на пункт приёмом из [from]: доза должна быть в единице пункта, а коробка — источником
     * плана [plan]; из любой другой это внеплановый факт, и пункт им не закрывается (PLAN D5).
     * Остаток коробки проверяет она сама.
     */
    fun take(amount: Dose, at: Instant, from: Package, plan: Course, countedHere: Boolean): Result<TakenDose> {
        if (amount.unit != unit) return Result.failure(IntakeRejected(IntakeRejected.Reason.UNIT_MISMATCH))
        val taken = from.take(amount, at, countedHere).getOrElse { return Result.failure(it) }
        if (!plan.isSource(from.ref)) return Result.failure(IntakeRejected(IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE))
        return Result.success(taken)
    }
}
