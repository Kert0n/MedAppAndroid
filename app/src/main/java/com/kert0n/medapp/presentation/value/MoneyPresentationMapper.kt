package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.presentation.ParsedInput
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParsePosition
import java.util.Currency
import java.util.Locale

/**
 * Предельная длина ввода следует из валюты, а не из одной константы.
 *
 * У рубля два знака после точки, у динара три, у иены нет ни одного — и общая «16» отвергала бы
 * `9999999999999.999`, которое для динара законно, как слишком длинное. Запас на ведущий ноль
 * оставлен: «0.50» человек печатает чаще, чем «.50».
 */
private fun maxInputLength(currency: Currency): Int {
    val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    val separator = if (fractionDigits > 0) 1 else 0
    return Money.MAX_INTEGER_DIGITS + separator + fractionDigits + 1
}

enum class MoneyPresentationError {
    EMPTY,
    TOO_LONG,
    NOT_A_DECIMAL,
    UNKNOWN_CURRENCY,
    OUT_OF_CURRENCY_RANGE
}

/**
 * Грамматика проверяется **до** `DecimalFormat`, а не вместо него.
 *
 * `DecimalFormat.parse` читает знак и экспоненту независимо от шаблона: разделитель экспоненты
 * берётся из символов локали и по умолчанию заглавный. Поэтому «1E3» разбирался в тысячу, «1E-2» —
 * в копейку, а «-0» проходил как ноль, хотя обещано число без знака и экспоненты. Шаблон здесь и
 * есть это обещание.
 */
private val DECIMAL_INPUT = Regex("""^\d+([.,]\d+)?$""")

/**
 * Приведение введённой цены к домену.
 *
 * `DecimalFormat` **изменяем и не потокобезопасен**, поэтому создаётся на каждый вызов, а не
 * хранится общим экземпляром: на восьми потоках общий экземпляр выдавал из «7890.12» число
 * «778899001122» и бросал исключения мимо результата.
 */
fun MoneyPresentationDTO.toDomain(): ParsedInput<Money, MoneyPresentationError> {
    val text = amount.trim()
    if (text.isEmpty()) return rejected(MoneyPresentationError.EMPTY)

    // Валюта разбирается первой: без неё неизвестно, какая длина ввода допустима.
    val currency = attempt { Currency.getInstance(currencyCode) }.getOrNull()
        ?: return rejected(MoneyPresentationError.UNKNOWN_CURRENCY)

    if (text.length > maxInputLength(currency)) return rejected(MoneyPresentationError.TOO_LONG)
    if (!DECIMAL_INPUT.matches(text)) return rejected(MoneyPresentationError.NOT_A_DECIMAL)

    val separator = if (text.contains(',')) ',' else '.'
    val parsed = decimalFormat(separator).parseFully(text)
        ?: return rejected(MoneyPresentationError.NOT_A_DECIMAL)

    return attempt { Money(parsed, currency) }.fold(
        onSuccess = { ParsedInput.Parsed(it) },
        onFailure = { rejected(MoneyPresentationError.OUT_OF_CURRENCY_RANGE) }
    )
}

private fun rejected(
    error: MoneyPresentationError
): ParsedInput<Money, MoneyPresentationError> = ParsedInput.Rejected(error)

/**
 * Свой экземпляр на вызов. Групповой разделитель задан явно и отличается от дробного: иначе
 * запятая означала бы сразу и то, и другое.
 */
private fun decimalFormat(separator: Char): DecimalFormat {
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
        decimalSeparator = separator
        groupingSeparator = if (separator == '.') ',' else '.'
    }
    return DecimalFormat("0", symbols).apply {
        isParseBigDecimal = true
        isGroupingUsed = false
    }
}

/**
 * Разобран должен быть весь ввод: `DecimalFormat` останавливается на первом непонятном символе
 * и молча отдаёт прочитанное.
 */
private fun DecimalFormat.parseFully(text: String): BigDecimal? {
    val position = ParsePosition(0)
    val parsed = parse(text, position) as? BigDecimal ?: return null
    return parsed.takeIf { position.index == text.length }
}
