package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.presentation.value.QuantityPresentationError

/**
 * Почему форму не записать. Причина — значение, а не текст: слова подбирает экран
 * (`R.string.*`), а разбор и сценарий называют случай (PLAN H1). У каждой причины есть
 * [field]: человек не ищет ошибку глазами.
 */
sealed interface CourseFormError {

    val field: Field

    enum class Field { TITLE, NOTE, DOSE, UNIT, FORM, START, DAYS, TIMES, TOTAL_DOSES, NONE }

    /** Названо человеком неверно. */
    enum class Input(override val field: Field) : CourseFormError {
        TITLE_EMPTY(Field.TITLE),
        TITLE_TOO_LONG(Field.TITLE),
        NOTE_TOO_LONG(Field.NOTE),
        UNIT_MISSING(Field.UNIT),
        DOSE_IS_ZERO(Field.DOSE),
        FORM_UNKNOWN(Field.FORM),
        START_MISSING(Field.START),
        DAYS_EMPTY(Field.DAYS),
        TIMES_EMPTY(Field.TIMES),
        TOTAL_DOSES_INVALID(Field.TOTAL_DOSES)
    }

    /** Число дозы не разобралось; чем именно — говорит разбор величины. */
    data class Dose(val error: QuantityPresentationError) : CourseFormError {
        override val field: Field get() = Field.DOSE
    }

    /** Сценарий отверг назначение: чего не хватает или что нельзя — по месту (PLAN D5). */
    data class Rejected(val reason: CourseRejected.Reason) : CourseFormError {
        override val field: Field
            get() = when (reason) {
                CourseRejected.Reason.SCHEDULE_MISSING, CourseRejected.Reason.SCHEDULE_IN_PAST -> Field.START
                CourseRejected.Reason.DOSE_MISSING, CourseRejected.Reason.UNIT_MISMATCH -> Field.DOSE
                CourseRejected.Reason.FORM_MISSING, CourseRejected.Reason.FORM_UNKNOWN,
                CourseRejected.Reason.FORM_MISMATCH -> Field.FORM
                CourseRejected.Reason.TOTAL_DOSES_MISSING -> Field.TOTAL_DOSES
                CourseRejected.Reason.ALREADY_ATTACHED, CourseRejected.Reason.PACKAGE_UNUSABLE -> Field.NONE
            }
    }

    /** Черновик правили с другого экрана: то, что здесь, устарело, и писать поверх нельзя (PLAN F5). */
    data object Stale : CourseFormError {
        override val field: Field get() = Field.NONE
    }
}
