package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Срок годности включителен, и это правило проверяется на обеих границах: знак сравнения
 * здесь — то, что при рефакторинге «поправят» первым (PLAN D3).
 */
class PackageExpiryTest {

    private val until31March = pack(expiresOn = expiry("2027-03-31"))

    @Test
    fun goodUntilTheThirtyFirstMeansTheThirtyFirstIsStillGood() {
        assertFalse(until31March.isExpiredOn(LocalDate.of(2027, 3, 31)))
    }

    @Test
    fun theFirstOfAprilIsExpired() {
        assertTrue(until31March.isExpiredOn(LocalDate.of(2027, 4, 1)))
    }

    @Test
    fun packWithoutAnExpiryDateIsNeverExpired() {
        // Отсутствующий срок не вычисляется и не выдумывается (PLAN D8).
        assertFalse(pack(expiresOn = null).isExpiredOn(LocalDate.of(2999, 1, 1)))
        assertFalse(pack(expiresOn = null).expiresWithin(LocalDate.of(2999, 1, 1), 3))
    }

    @Test
    fun thresholdIsAWindowAndNotAnExactDay() {
        // Фоновая задача может задержаться: «ровно за три дня» она бы перепрыгнула.
        val threeDaysBefore = LocalDate.of(2027, 3, 28)
        assertTrue(until31March.expiresWithin(threeDaysBefore, 3))
        assertTrue(until31March.expiresWithin(LocalDate.of(2027, 3, 30), 3))
        assertTrue(until31March.expiresWithin(LocalDate.of(2027, 3, 31), 3))
        assertFalse(until31March.expiresWithin(LocalDate.of(2027, 3, 27), 3))
    }

    @Test
    fun expiredPackIsNotExpiringSoon() {
        // У просрочки своё состояние и своё сообщение; смешивать их значило бы показать
        // «истекает через три дня» на пачке, которая просрочена месяц назад.
        assertFalse(until31March.expiresWithin(LocalDate.of(2027, 4, 1), 3))
    }

    @Test
    fun lastDayWindowIsZeroDays() {
        assertTrue(until31March.expiresWithin(LocalDate.of(2027, 3, 31), 0))
        assertFalse(until31March.expiresWithin(LocalDate.of(2027, 3, 30), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWindowIsRejected() {
        until31March.expiresWithin(LocalDate.of(2027, 3, 31), -1)
    }

    @Test
    fun theRuleAnswersTheSameWithoutAPack() {
        // Проекция доступного несёт один срок и никакой пачки (PLAN D4), и ответ обязан совпадать:
        // два `isBefore` в двух местах — это два знака, которые разойдутся.
        val expires = expiry("2027-03-31")
        listOf(
            LocalDate.of(2027, 3, 27),
            LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 4, 1)
        ).forEach { on ->
            assertEquals(until31March.isExpiredOn(on), expires.isExpiredOn(on))
            assertEquals(until31March.expiresWithin(on, 3), expires.expiresWithin(on, 3))
        }
    }

    @Test
    fun namedMonthIsGoodUntilItsLastDay() {
        // «03.2027» — не первое марта: иначе у пачки отняли бы почти месяц годности (PLAN D3).
        assertEquals(expiry("2027-03-31"), ExpiryDate.of(YearMonth.of(2027, 3)))
    }

    @Test
    fun twoDifferentTermsAreDifferentValues() {
        // Величина, а не сущность: сравнивается содержимым, и это то, на что смотрит экран.
        assertEquals(expiry("2027-03-31"), expiry("2027-03-31"))
        assertNotEquals(expiry("2027-03-31"), expiry("2027-04-01"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun intakeHintInAnotherUnitIsRejected() {
        pack(quantity = tablets("20"), defaultIntakeAmount = dose(millilitres("5")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptionOverTheLimitIsRejected() {
        pack(description = "я".repeat(PackageSharedFacts.DESCRIPTION_MAX_LENGTH + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyNoteIsNotAWayToSayThereIsNone() {
        pack(note = "")
    }
}
