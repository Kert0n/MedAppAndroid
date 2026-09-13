package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три величины «сколько доступно» (PLAN D4). Свёртки очереди здесь нет вовсе: оценка количества
 * приходит готовой, и это её единственный вход.
 */
class PackageAvailabilityTest {

    private val today: LocalDate = LocalDate.of(2027, 3, 1)

    /** Пачка без броней с известным количеством [amount]. */
    private fun known(stored: String, amount: String = stored) = PackageAvailability(
        pkg = pack(quantity = tablets(stored)),
        effective = tablets(amount)
    )

    @Test
    fun numbersFromPlanAreReproduced() {
        // Пример PLAN D4: на сервере 20, моя бронь 10, чужого 5, локально принято 3 и выделение
        // уменьшилось до 7. Оценка 17 уже посчитана слоем данных.
        val found = PackageAvailability(
            pkg = pack(quantity = tablets("20"), claims = Claims(BigDecimal("15"), BigDecimal("10"))),
            effective = tablets("17"),
            myAllocation = tablets("7")
        )
        assertEquals(tablets("17"), found.effective)
        assertEquals(tablets("5"), found.reservedByOthers)
        assertEquals(tablets("5"), found.freeForAnyone)
        assertEquals(tablets("12"), found.availableToMe)
    }

    @Test
    fun unpublishedKitStillHasAllocations() {
        // Броней сервера нет, но из двадцати таблеток пятнадцать отданы курсу: свободно пять.
        val local = PackageAvailability(
            pkg = pack(quantity = tablets("20")),
            effective = tablets("20"),
            myAllocation = tablets("15")
        )
        assertEquals(tablets("0"), local.reservedByOthers)
        assertEquals(tablets("5"), local.freeForAnyone)
        assertEquals(tablets("20"), local.availableToMe)
    }

    @Test
    fun consumptionOfOneTabletMakesADifferentValue() {
        // Все поля — числа, поэтому расход 20 → 19 даёт другое значение (C1 «Состояние экрана»).
        val before = known("20")
        val after = known("19")
        assertNotEquals(before, after)
        assertNotEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun sameNumbersAreTheSameValue() {
        val one = known("20")
        val other = known("20", amount = "20.000000")
        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
    }

    @Test
    fun myOwnClaimDoesNotReduceWhatIsAvailableToMe() {
        // Заявил её я сам, и другого владельца у неё не бывает: вычитать её из своего же
        // доступного значило бы отнять у себя собственные таблетки (замечание PR 6).
        val mine = PackageAvailability(
            pkg = pack(quantity = tablets("20"), claims = Claims(BigDecimal("15"), BigDecimal("10"))),
            effective = tablets("20"),
            myAllocation = tablets("10")
        )
        assertEquals(tablets("5"), mine.reservedByOthers)
        assertEquals(tablets("15"), mine.availableToMe)
        assertEquals(tablets("5"), mine.freeForAnyone)
    }

    @Test
    fun claimWithoutALocalCourseIsStillMine() {
        // Локального курса за бронью может не быть, но источник истины — устройство, и следующая
        // команда брони приведёт сервер в согласие. Домен на это число не смотрит.
        val unexplained = PackageAvailability(
            pkg = pack(quantity = tablets("20"), claims = Claims(BigDecimal("10"), BigDecimal("10"))),
            effective = tablets("20")
        )
        assertEquals(tablets("0"), unexplained.reservedByOthers)
        assertEquals(tablets("20"), unexplained.availableToMe)
        assertEquals(tablets("20"), unexplained.freeForAnyone)
    }

    @Test
    fun expiryIsAskedByDateAndOnlyMarks() {
        val expiring = PackageAvailability(
            pkg = pack(quantity = tablets("20"), expiresOn = ExpiryDate(today.plusDays(2))),
            effective = tablets("20")
        )
        assertFalse(expiring.isExpiredOn(today))
        assertTrue(expiring.expiresSoonOn(today))
        assertTrue(expiring.isExpiredOn(today.plusDays(3)))
        // Просрочка ничего не списывает: пачка остаётся источником.
        assertEquals(tablets("20"), expiring.effective)
    }

    @Test
    fun negativeClaimPictureNeverProducesANegativeNumber() {
        // Сумма броней может превышать остаток: показываем ноль, а не долг.
        val over = PackageAvailability(
            pkg = pack(quantity = tablets("2"), claims = Claims(BigDecimal("30"), null)),
            effective = tablets("2")
        )
        assertEquals(tablets("0"), over.availableToMe)
        assertEquals(tablets("0"), over.freeForAnyone)
    }

    @Test
    fun unitsAreNotMixed() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageAvailability(
                pkg = pack(quantity = tablets("20")),
                effective = millilitres("20")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackageAvailability(
                pkg = pack(quantity = tablets("20")),
                effective = tablets("20"),
                myAllocation = millilitres("5")
            )
        }
    }
}
