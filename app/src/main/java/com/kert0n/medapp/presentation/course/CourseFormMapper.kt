package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.LocalDate

/**
 * Разбор формы лечения: строки экрана — в то, что примет сценарий.
 *
 * **Черновику обязательно только название**; каждое из остальных полей либо пусто — и тогда его
 * не отправляют, — либо заполнено и разбирается. Полнота назначения здесь не проверяется: её
 * требует начало лечения, и отказ приходит от сценария ([CourseFormError.Rejected]) — второй
 * проверки экран не держит (D5 «Черновик»).
 *
 * Пробелы вокруг названия — не часть его. Пустая заметка — **отсутствие**. Пределы берутся у
 * записи эпизода ([CourseRecord.TITLE_MAX_LENGTH], [CourseRecord.NOTE_MAX_LENGTH]). Число дозы
 * разбирает тот же разбор, что количество коробки; доза в ноль — не доза. Расписание либо целиком,
 * либо никак: начало без дней — не половина расписания, а незаконченное поле. Времена — минуты
 * без секунд, без повторов, по порядку: этого требует само расписание (D5), и экран его не
 * переспрашивает — приводит.
 */
fun CourseFormPresentationDTO.parsed(vocabulary: Vocabulary): ParsedInput<CourseDescription, CourseFormError> {
    val title = title.trim()
    val note = note.trim().ifEmpty { null }
    if (title.isEmpty()) return rejected(CourseFormError.Input.TITLE_EMPTY)
    if (title.length > CourseRecord.TITLE_MAX_LENGTH) return rejected(CourseFormError.Input.TITLE_TOO_LONG)
    if ((note?.length ?: 0) > CourseRecord.NOTE_MAX_LENGTH) return rejected(CourseFormError.Input.NOTE_TOO_LONG)

    val dose = doseAmount.trim().ifEmpty { null }?.let { typed ->
        val unit = unit ?: return rejected(CourseFormError.Input.UNIT_MISSING)
        when (val parsed = QuantityPresentationDTO(typed, unit).toDomain(vocabulary)) {
            is ParsedInput.Rejected -> return rejected(CourseFormError.Dose(parsed.error))
            is ParsedInput.Parsed ->
                if (parsed.value.isZero) return rejected(CourseFormError.Input.DOSE_IS_ZERO) else Dose(parsed.value)
        }
    }

    // Форма выбирается из снимка, но снимок мог устареть: промах — отказ, а не падение.
    val form: DosageForm? = form?.let { chosen ->
        vocabulary.form(chosen.id) ?: return rejected(CourseFormError.Input.FORM_UNKNOWN)
    }

    val schedule = when (val parsed = scheduleParsed()) {
        is ParsedInput.Rejected -> return rejected(parsed.error)
        is ParsedInput.Parsed -> parsed.value
    }

    val totalDoses = totalDoses.trim().ifEmpty { null }?.let { typed ->
        typed.toIntOrNull()?.takeIf { it > 0 }?.let(::Doses)
            ?: return rejected(CourseFormError.Input.TOTAL_DOSES_INVALID)
    }

    return ParsedInput.Parsed(CourseDescription(title, note, dose, form, schedule, totalDoses))
}

/**
 * Расписание из трёх полей — целиком или никак. Ничего не названо — расписания нет, и это не
 * отказ; названо хоть что-то — не хватать не должно ничего. Отдельно от [parsed], потому что
 * ожидаемый конец считается по нему при каждом вводе, а не только при записи.
 */
fun CourseFormPresentationDTO.scheduleParsed(): ParsedInput<CourseSchedule?, CourseFormError.Input> {
    if (start == null && days.isEmpty() && times.isEmpty()) return ParsedInput.Parsed(null)
    val start = start ?: return ParsedInput.Rejected(CourseFormError.Input.START_MISSING)
    if (days.isEmpty()) return ParsedInput.Rejected(CourseFormError.Input.DAYS_EMPTY)
    if (times.isEmpty()) return ParsedInput.Rejected(CourseFormError.Input.TIMES_EMPTY)
    return ParsedInput.Parsed(
        CourseSchedule(
            start = start,
            daysOfWeek = days,
            times = times.map { it.withSecond(0).withNano(0) }.distinct().sorted(),
            zone = zone
        )
    )
}

/**
 * Когда ожидается последний приём: по расписанию и числу приёмов, от начала. Человеку это
 * говорит, на сколько дней он записал лечение, — «дата конца» из ТЗ здесь читается, а не
 * вводится (C1 «Курс резиновый»). Пока нет расписания или числа — нечего считать.
 */
fun CourseFormPresentationDTO.expectedEnd(): LocalDate? {
    val schedule = (scheduleParsed() as? ParsedInput.Parsed)?.value ?: return null
    val count = totalDoses.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    return schedule.next(schedule.beginning, count).lastOrNull()?.localDate
}

/** Записанный черновик — обратно в форму: человек правит то, что видел. */
fun CourseDraftProjection.toFormPresentationDTO(): CourseFormPresentationDTO = CourseFormPresentationDTO(
    title = title,
    note = note.orEmpty(),
    doseAmount = dose?.quantity?.toPresentationDTO()?.amount.orEmpty(),
    unit = dose?.unit?.toPresentationDTO(),
    form = form?.toPresentationDTO(),
    start = schedule?.start,
    days = schedule?.daysOfWeek.orEmpty(),
    times = schedule?.times.orEmpty(),
    totalDoses = totalDoses?.count?.toString().orEmpty(),
    zone = schedule?.zone ?: java.time.ZoneId.systemDefault()
)

/**
 * Что человек назвал, уже проверенное: это и принимает сценарий. Чего нет — не названо, а не
 * «пустое»: черновик пишется по частям.
 */
data class CourseDescription(
    val title: String,
    val note: String?,
    val dose: Dose? = null,
    val form: DosageForm? = null,
    val schedule: CourseSchedule? = null,
    val totalDoses: Doses? = null
)

private fun rejected(error: CourseFormError): ParsedInput<CourseDescription, CourseFormError> = ParsedInput.Rejected(error)
