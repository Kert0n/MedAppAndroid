package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.domain.value.Money
import java.math.BigDecimal
import java.util.Currency
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приведение введённой цены к домену: грамматику обещает маппер, он же её и проверяет. */
class MoneyPresentationMapperTest {

    private fun mapped(input: String, currencyCode: String = "RUB") =
        MoneyPresentationDTO(input, currencyCode).toDomain()

    private fun errorOf(input: String, currencyCode: String = "RUB"): MoneyPresentationError {
        val error = mapped(input, currencyCode).errorOrNull
        assertTrue("ожидался отказ на «$input»", error != null)
        return requireNotNull(error)
    }

    @Test
    fun bothSeparatorsAreAccepted() {
        assertEquals(Money(BigDecimal("1.50")), mapped("1,50").valueOrNull)
        assertEquals(Money(BigDecimal("1.50")), mapped("1.50").valueOrNull)
    }

    @Test
    fun exponentIsRejectedInBothCases() {
        // `DecimalFormat` читает экспоненту независимо от шаблона, поэтому грамматика формы
        // отвергает её до разбора как пользовательскую десятичную запись.
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("1E3"))
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("1e3"))
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("1E-2"))
    }

    @Test
    fun signIsRejectedEvenWhenItChangesNothing() {
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("-0"))
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("-1"))
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("+1"))
    }

    @Test
    fun groupedAndEmptyInputIsRejected() {
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("1 200"))
        assertEquals(MoneyPresentationError.NOT_A_DECIMAL, errorOf("сто"))
        assertEquals(MoneyPresentationError.EMPTY, errorOf("   "))
        assertEquals(MoneyPresentationError.TOO_LONG, errorOf("1".repeat(64)))
    }

    @Test
    fun boundaryPriceOfAThreeDigitCurrencyFits() {
        // 13 разрядов до точки и три после — законная цена для динара; общий предел длины в 16
        // символов отвергал её как «слишком длинную», хотя домен её принимает.
        val boundary = "9999999999999.999"
        assertEquals(
            Money(BigDecimal(boundary), Currency.getInstance("KWD")),
            mapped(boundary, "KWD").valueOrNull
        )
    }

    @Test
    fun lengthLimitFollowsTheCurrency() {
        // У иены дробной части нет, поэтому точка в её вводе не помещается вовсе.
        assertEquals(MoneyPresentationError.TOO_LONG, errorOf("9999999999999.999", "JPY"))
    }

    @Test
    fun unknownCurrencyIsItsOwnRefusal() {
        assertEquals(MoneyPresentationError.UNKNOWN_CURRENCY, errorOf("1.50", "rub"))
    }

    @Test
    fun currencyRangeIsReportedSeparatelyFromGrammar() {
        assertEquals(MoneyPresentationError.OUT_OF_CURRENCY_RANGE, errorOf("1.005"))
        // У иены дробной части в расчётах нет: запись верна, цена — нет.
        assertEquals(MoneyPresentationError.OUT_OF_CURRENCY_RANGE, errorOf("1,50", "JPY"))
    }

    @Test
    fun mappingIsCorrectUnderConcurrentUse() {
        // `DecimalFormat` изменяем: на общем экземпляре восемь потоков превращали «7890.12» в
        // «778899001122» и бросали исключения мимо результата. Свой экземпляр на вызов это снимает.
        val expected = Money(BigDecimal("7890.12"))
        val pool = Executors.newFixedThreadPool(THREADS)
        val tasks = List(THREADS) {
            Callable {
                var wrong = 0
                var escaped = 0
                repeat(REPEATS) {
                    try {
                        if (mapped("7890.12").valueOrNull != expected) wrong++
                    } catch (cause: Throwable) {
                        escaped++
                    }
                }
                wrong to escaped
            }
        }
        val results = pool.invokeAll(tasks).map { it.get() }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals("неверных приведений", 0, results.sumOf { it.first })
        assertEquals("исключений наружу", 0, results.sumOf { it.second })
    }

    private companion object {
        const val THREADS = 8
        const val REPEATS = 200
    }
}
