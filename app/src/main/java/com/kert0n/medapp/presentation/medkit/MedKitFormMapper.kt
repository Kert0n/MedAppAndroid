package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.presentation.ParsedInput

/**
 * Разбор формы аптечки: строки экрана — в то, что примет сценарий.
 *
 * Пробелы вокруг названия — не часть его: человек их не видит, а домен отличил бы «Дом» от
 * «Дом ». Пустое место хранения — **отсутствие**, а не пустая строка: пустая строка означала бы
 * «человек сказал, что места нет».
 *
 * Пределы берутся у самой аптечки ([MedKit.NAME_MAX_LENGTH], [MedKit.LOCATION_MAX_LENGTH]) —
 * повтори их здесь числом, и правило разъехалось бы с тем, о чём оно.
 */
fun MedKitFormPresentationDTO.parsed(): ParsedInput<MedKitDescription, MedKitFormError.Input> {
    val name = name.trim()
    val location = location.trim().ifEmpty { null }
    return when {
        name.isEmpty() -> ParsedInput.Rejected(MedKitFormError.Input.NAME_EMPTY)
        name.length > MedKit.NAME_MAX_LENGTH -> ParsedInput.Rejected(MedKitFormError.Input.NAME_TOO_LONG)
        (location?.length ?: 0) > MedKit.LOCATION_MAX_LENGTH ->
            ParsedInput.Rejected(MedKitFormError.Input.LOCATION_TOO_LONG)
        else -> ParsedInput.Parsed(MedKitDescription(name, location))
    }
}

/** Записанная аптечка — обратно в форму: человек правит то, что видел. */
fun MedKitProjection.toFormPresentationDTO(): MedKitFormPresentationDTO =
    MedKitFormPresentationDTO(name = name, location = location.orEmpty())
