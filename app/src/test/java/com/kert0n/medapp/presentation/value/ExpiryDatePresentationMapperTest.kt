package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.domain.pack.ExpiryDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryDatePresentationMapperTest {

    @Test
    fun printedMonthBecomesItsLastDay() {
        // На упаковках печатают «03.2027». Первое марта было бы потерей почти целого месяца
        // годности, и человек видит развёрнутую дату до сохранения.
        assertEquals(ExpiryDate(LocalDate.of(2027, 3, 31)), expiryOf("03.2027"))
    }

    @Test
    fun leapFebruaryGetsItsTwentyNinth() {
        assertEquals(ExpiryDate(LocalDate.of(2028, 2, 29)), expiryOf("02.2028"))
        assertEquals(ExpiryDate(LocalDate.of(2027, 2, 28)), expiryOf("02.2027"))
    }

    @Test
    fun fullDatesAreTakenAsTheyAre() {
        val expected = ExpiryDate(LocalDate.of(2027, 3, 31))
        assertEquals(expected, expiryOf("31.03.2027"))
        assertEquals(expected, expiryOf("31/03/2027"))
        assertEquals(expected, expiryOf("2027-03-31"))
    }

    @Test
    fun singleDigitMonthAndDayAreAccepted() {
        // На упаковках печатают и «3.2027», и «03.2027».
        assertEquals(ExpiryDate(LocalDate.of(2027, 3, 31)), expiryOf("3.2027"))
        assertEquals(ExpiryDate(LocalDate.of(2027, 3, 1)), expiryOf("1.3.2027"))
    }

    @Test
    fun leapYearRulesComeFromTheCalendarAndNotFromUs() {
        // 2000 — високосный, 1900 — нет, хотя оба делятся на четыре и на сто.
        assertEquals(ExpiryDate(LocalDate.of(2000, 2, 29)), expiryOf("02.2000"))
        assertEquals(ExpiryDate(LocalDate.of(1900, 2, 28)), expiryOf("02.1900"))
        assertEquals(ExpiryDatePresentationError.IMPOSSIBLE_DATE, errorOf("29.02.1900"))
    }

    @Test
    fun isoMonthIsAlsoAMonth() {
        assertEquals(ExpiryDate(LocalDate.of(2027, 3, 31)), expiryOf("2027-03"))
    }

    @Test
    fun alreadyExpiredInputIsAccepted() {
        // ТЗ 4.1.2: «реалистично некорректные» данные принимаются и отрабатываются.
        assertEquals(ExpiryDate(LocalDate.of(2001, 1, 31)), expiryOf("01.2001"))
    }

    @Test
    fun impossibleDatesAreNamedAsSuch() {
        assertEquals(ExpiryDatePresentationError.IMPOSSIBLE_DATE, errorOf("13.2027"))
        assertEquals(ExpiryDatePresentationError.IMPOSSIBLE_DATE, errorOf("32.03.2027"))
        assertEquals(ExpiryDatePresentationError.IMPOSSIBLE_DATE, errorOf("29.02.2027"))
    }

    @Test
    fun unrecognisedInputIsNamedAsSuch() {
        assertEquals(ExpiryDatePresentationError.UNKNOWN_FORMAT, errorOf("2027"))
        assertEquals(ExpiryDatePresentationError.UNKNOWN_FORMAT, errorOf("март 2027"))
        assertEquals(ExpiryDatePresentationError.UNKNOWN_FORMAT, errorOf("03.27"))
    }

    @Test
    fun emptyInputIsNamedAsSuch() {
        assertEquals(ExpiryDatePresentationError.EMPTY, errorOf("   "))
    }

    private fun mapped(input: String) = ExpiryDatePresentationDTO(input).toDomain()

    /** Маппер отдаёт домену готовый срок, поэтому и ожидание здесь — срок, а не дата. */
    private fun expiryOf(input: String): ExpiryDate = requireNotNull(mapped(input).valueOrNull)

    private fun errorOf(input: String): ExpiryDatePresentationError {
        val error = mapped(input).errorOrNull
        assertTrue("ожидался отказ на «$input»", error != null)
        return requireNotNull(error)
    }
}
