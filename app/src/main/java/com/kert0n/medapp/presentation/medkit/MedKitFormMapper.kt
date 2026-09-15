package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.presentation.ParsedInput

/**
 * Разбор формы полки. Домен требует готовые значения, и как строка ими стала — забота
 * представления (PLAN H1): пробелы вокруг названия не часть его, а место хранения из одних
 * пробелов — это отсутствие места, а не пустая строка.
 *
 * Пределы берутся у [MedKit] — у типа, который их держит: повторить их числом здесь значило бы
 * завести вторую правду, которая разойдётся с первой.
 */
fun MedKitFormPresentationDTO.parsed(): ParsedInput<MedKitDescription, MedKitFormError.Input> {
    val name = name.trim()
    val location = location.trim().ifEmpty { null }
    return when {
        name.isEmpty() -> ParsedInput.Rejected(MedKitFormError.Input.NAME_EMPTY)
        name.length > MedKit.NAME_MAX_LENGTH -> ParsedInput.Rejected(MedKitFormError.Input.NAME_TOO_LONG)
        location != null && location.length > MedKit.LOCATION_MAX_LENGTH ->
            ParsedInput.Rejected(MedKitFormError.Input.LOCATION_TOO_LONG)
        else -> ParsedInput.Parsed(MedKitDescription(name, location))
    }
}

/** Открытая на правку форма показывает записанное: пустое место хранения — пустое поле. */
fun MedKitProjection.toFormPresentationDTO(): MedKitFormPresentationDTO =
    MedKitFormPresentationDTO(name = name, location = location.orEmpty())
