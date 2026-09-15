package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.presentation.ParsedInput
import java.text.ParsePosition
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class ExpiryDatePresentationError {
    EMPTY,
    UNKNOWN_FORMAT,
    IMPOSSIBLE_DATE
}

/**
 * `uuuu`, а не `yyyy`: строгое разрешение требует год пролептического календаря, иначе оно
 * потребовало бы ещё и эру. `M` и `d` принимают и одну цифру, и две — на упаковках печатают
 * и «3.2027», и «03.2027». `DateTimeFormatter` неизменяем и потокобезопасен, поэтому общие
 * экземпляры здесь безопасны, в отличие от `DecimalFormat`.
 */
private val MONTH_FORMATS = listOf("M.uuuu", "M/uuuu", "uuuu-MM").map(::strictFormat)

private val DAY_FORMATS = listOf("d.M.uuuu", "d/M/uuuu", "uuuu-MM-dd").map(::strictFormat)

private fun strictFormat(pattern: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)

/**
 * Приведение введённого срока годности к дате.
 *
 * Здесь **только распознавание записи**: какие шаблоны бывают на упаковке и что человек мог
 * напечатать. Продуктовое правило — что месяц означает его последний день — живёт в домене
 * ([ExpiryDate.of]), потому что это решение о годности, а не о форме ввода. Разделение ровно
 * такое: «03.2027» распознаёт маппер, последним днём марта его делает домен.
 *
 * Домену отдаётся готовый [ExpiryDate], а не дата: тип уже говорит, что записан последний годный
 * день, и вопрос «включительно ли» до домена не доезжает (PLAN C1 «Разбор ввода»).
 *
 * Прошедшая дата принимается: ТЗ 4.1.2 прямо требует принимать «реалистично некорректные» данные
 * и отрабатывать их.
 */
fun ExpiryDatePresentationDTO.toDomain():
    ParsedInput<ExpiryDate, ExpiryDatePresentationError> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ParsedInput.Rejected(ExpiryDatePresentationError.EMPTY)

    for (format in MONTH_FORMATS) {
        if (!matchesShape(trimmed, format)) continue
        return resolve { ExpiryDate.of(YearMonth.parse(trimmed, format)) }
    }
    for (format in DAY_FORMATS) {
        if (!matchesShape(trimmed, format)) continue
        return resolve { ExpiryDate(LocalDate.parse(trimmed, format)) }
    }
    return ParsedInput.Rejected(ExpiryDatePresentationError.UNKNOWN_FORMAT)
}

/**
 * Совпал ли ввод с шаблоном по форме, без проверки самих значений.
 *
 * [DateTimeFormatter.parseUnresolved] разбирает поля, но не сводит их в дату, поэтому тринадцатый
 * месяц на этом шаге ещё проходит. Так и различаются два разных отказа: «март 2027» не похож на
 * дату вовсе, а «13.2027» похож, но такой даты не существует.
 */
private fun matchesShape(text: String, format: DateTimeFormatter): Boolean {
    val position = ParsePosition(0)
    format.parseUnresolved(text, position) ?: return false
    return position.index == text.length
}

private fun resolve(
    parse: () -> ExpiryDate
): ParsedInput<ExpiryDate, ExpiryDatePresentationError> = attempt(parse).fold(
    onSuccess = { ParsedInput.Parsed(it) },
    onFailure = { ParsedInput.Rejected(ExpiryDatePresentationError.IMPOSSIBLE_DATE) }
)

/**
 * Срок обратно в строку — для формы, открытой на правку: человек должен увидеть записанное в том
 * виде, в каком он его печатал.
 *
 * Месяц показывается месяцем. Домен хранит последний годный день, и «03.2027» лежит там как
 * 31.03.2027; показать его днём значило бы дописать за человека точность, которой он не называл.
 * День показывается днём — его и печатали.
 */
fun ExpiryDate.toPresentationDTO(): ExpiryDatePresentationDTO {
    val month = YearMonth.from(lastDay)
    return ExpiryDatePresentationDTO(
        if (lastDay == month.atEndOfMonth()) month.format(MONTH_OUT) else lastDay.format(DAY_OUT)
    )
}

private val MONTH_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.uuuu")

private val DAY_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")
