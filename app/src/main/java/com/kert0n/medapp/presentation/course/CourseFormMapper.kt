package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.presentation.ParsedInput

/**
 * Разбор формы лечения: строки экрана — в то, что примет сценарий.
 *
 * Пробелы вокруг названия — не часть его. Пустая заметка — **отсутствие**, а не пустая строка.
 * Пределы берутся у записи эпизода ([CourseRecord.TITLE_MAX_LENGTH], [CourseRecord.NOTE_MAX_LENGTH]):
 * повтори их здесь числом, и правило разъехалось бы с тем, о чём оно.
 */
fun CourseFormPresentationDTO.parsed(): ParsedInput<CourseDescription, CourseFormError.Input> {
    val title = title.trim()
    val note = note.trim().ifEmpty { null }
    return when {
        title.isEmpty() -> ParsedInput.Rejected(CourseFormError.Input.TITLE_EMPTY)
        title.length > CourseRecord.TITLE_MAX_LENGTH -> ParsedInput.Rejected(CourseFormError.Input.TITLE_TOO_LONG)
        (note?.length ?: 0) > CourseRecord.NOTE_MAX_LENGTH -> ParsedInput.Rejected(CourseFormError.Input.NOTE_TOO_LONG)
        else -> ParsedInput.Parsed(CourseDescription(title, note))
    }
}

/** Записанный черновик — обратно в форму: человек правит то, что видел. */
fun CourseDraftProjection.toFormPresentationDTO(): CourseFormPresentationDTO =
    CourseFormPresentationDTO(title = title, note = note.orEmpty())

/** Что человек назвал, уже проверенное: это и принимает сценарий. */
data class CourseDescription(val title: String, val note: String?)
