package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.ParsedInput
import java.math.BigDecimal

/** 13 разрядов, точка и 6 знаков, плюс запас на ведущий ноль. Свойство поля, не величины. */
const val QUANTITY_MAX_INPUT_LENGTH = 21

enum class QuantityPresentationError {
    EMPTY,
    TOO_LONG,
    NOT_A_DECIMAL,
    TOO_MANY_FRACTION_DIGITS,
    TOO_MANY_INTEGER_DIGITS,
    OUT_OF_DOMAIN_RANGE,

    /** Единицы нет в снимке словаря: он старее, чем тот, кто её назвал (PLAN D1). */
    UNKNOWN_UNIT
}

/** Шаблон — сам контракт B2: сервер отвергнет знак и экспоненту ровно так же. */
private val DECIMAL_INPUT = Regex("""^\d+(\.\d+)?$""")

/**
 * Приведение введённого количества к домену.
 *
 * Домен требует готовую величину и не знает, что на клавиатуре бывает запятая, что у поля есть
 * предельная длина и что вставленную из буфера простыню надо отсечь до разбора. Всё это — свойства
 * ввода, и живут они здесь.
 *
 * Единицу даёт [vocabulary] по номеру: экран держал её имя, а домену нужен объект словаря, и
 * подсовывать вместо него собранный из полей экрана нельзя — тождество единицы принадлежит
 * словарю. Промах — `UNKNOWN_UNIT`: снимок старее, чем тот, кто единицу назвал (PLAN D1).
 */
fun QuantityPresentationDTO.toDomain(vocabulary: Vocabulary): ParsedInput<Quantity, QuantityPresentationError> {
    val text = amount.trim().replace(',', '.')
    reject(text)?.let { return ParsedInput.Rejected(it) }
    val known = vocabulary.unit(unit.id)
        ?: return ParsedInput.Rejected(QuantityPresentationError.UNKNOWN_UNIT)
    // Последнее слово за величиной: её нынешние пределы здесь известны, но менять их вправе домен,
    // и тогда отказ должен остаться отказом, а не исключением наружу.
    return attempt { Quantity(BigDecimal(text), known) }
        .fold(
            onSuccess = { ParsedInput.Parsed(it) },
            onFailure = {
                ParsedInput.Rejected(QuantityPresentationError.OUT_OF_DOMAIN_RANGE)
            }
        )
}

private fun reject(text: String): QuantityPresentationError? {
    if (text.isEmpty()) return QuantityPresentationError.EMPTY
    if (text.length > QUANTITY_MAX_INPUT_LENGTH) return QuantityPresentationError.TOO_LONG
    if (!DECIMAL_INPUT.matches(text)) return QuantityPresentationError.NOT_A_DECIMAL
    val dot = text.indexOf('.')
    val integerDigits = if (dot < 0) text.length else dot
    val fractionDigits = if (dot < 0) 0 else text.length - dot - 1
    if (fractionDigits > Quantity.SCALE) return QuantityPresentationError.TOO_MANY_FRACTION_DIGITS
    if (integerDigits > Quantity.MAX_INTEGER_DIGITS) {
        return QuantityPresentationError.TOO_MANY_INTEGER_DIGITS
    }
    return null
}
