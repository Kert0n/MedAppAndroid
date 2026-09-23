package com.kert0n.medapp.domain.course

import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.doses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Нехватка и уменьшившаяся потребность зажимают выделение: каждой пачке — не больше целых доз,
 * что в ней есть, избыток снимается с конца, а расписание не трогается (PLAN D5, C1).
 */
class CourseShortageTest {

    private val twoPacks = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 4)))

    private val plenty = availability(PACK to tablets("20"), OTHER_PACK to tablets("12"))

    private fun Course.allocations() = medicine.sources.map { it.allocatedDoses }

    @Test
    fun shortageLowersOnlyThePackThatLostStock() {
        // В первой пачке четыре таблетки — две дозы: выделение зажато до двух, вторая не тронута.
        val shrunk = availability(PACK to tablets("4"), OTHER_PACK to tablets("12"))
        val clamped = twoPacks.clamped(28.doses, shrunk, LATER)
        assertEquals(listOf(2.doses, 4.doses), clamped.allocations())
    }

    @Test
    fun clampingChangesOnlyAllocations() {
        // Нехватка меняет обеспечение, а не назначение: доза, расписание и форма после зажима те
        // же, и «сократить курс до возможного срока» из исходных требований невыразимо (C1).
        val empty = availability(PACK to tablets("0"), OTHER_PACK to tablets("0"))
        val after = twoPacks.clamped(28.doses, empty, LATER)
        assertEquals(listOf(0.doses, 0.doses), after.allocations())
        assertEquals(twoPacks.schedule, after.schedule)
        assertEquals(twoPacks.dose, after.dose)
        assertEquals(twoPacks.form, after.form)
    }

    @Test
    fun shortageShowsUpAsCoverageAndNamesTheFirstGap() {
        // Человеку — «нужно 7, обеспечено 2, не хватает с третьего приёма», а не сдвинутые даты.
        val week = schedule()
        val plan = week.next(week.beginning, 7).toList()
        val shrunk = availability(PACK to tablets("4"), OTHER_PACK to tablets("0"))
        val found = twoPacks.clamped(plan.size.doses, shrunk, LATER)
            .coverage(CourseProgress.none, shrunk)
        assertEquals(7.doses, found.requiredDoses)
        assertEquals(2.doses, found.coveredDoses)
        assertEquals(plan[2].at, found.firstUncoveredAt)
    }

    @Test
    fun grownStockDoesNotRaiseTheAllocationByItself() {
        // Выделение — решение человека, а не следствие поставки.
        val grown = availability(PACK to tablets("100"), OTHER_PACK to tablets("100"))
        val clamped28 = twoPacks.clamped(28.doses, grown, LATER)
        assertEquals(listOf(5.doses, 4.doses), clamped28.allocations())
    }

    @Test
    fun packageHintDoesNotChangeTheClamp() {
        // Доза-подсказка упаковки личная и к лечению отношения не имеет (PLAN D5, C1).
        val hinted = pack(quantity = tablets("20"))
            .describe(factsOf(pack()).copy(defaultIntakeAmount = dose("1")))
        val available = availability(PACK to hinted.quantity, OTHER_PACK to tablets("12"))
        val clamped = twoPacks.clamped(28.doses, available, LATER)
        assertEquals(listOf(5.doses, 4.doses), clamped.allocations())
    }

    @Test
    fun skipReleasesADoseFromTheEndOfTheMedicine() {
        // Пропуск уменьшил потребность с девяти до восьми: освободилась доза нижней пачки, а та,
        // из которой человек принимает, не тронута.
        val clamped = twoPacks.clamped(8.doses, plenty, LATER)
        assertEquals(listOf(5.doses, 3.doses), clamped.allocations())
    }

    @Test
    fun excessOverTheNeedIsTakenFromTheEnd() {
        val clamped = twoPacks.clamped(6.doses, plenty, LATER)
        assertEquals(listOf(5.doses, 1.doses), clamped.allocations())
    }

    @Test
    fun trimmingWalksUpWhenTheTailIsExhausted() {
        val clamped4 = twoPacks.clamped(4.doses, plenty, LATER)
        assertEquals(listOf(4.doses, 0.doses), clamped4.allocations())
        val clamped0 = twoPacks.clamped(0.doses, plenty, LATER)
        assertEquals(listOf(0.doses, 0.doses), clamped0.allocations())
    }

    @Test
    fun allocationWithinTheNeedIsLeftAlone() {
        // Тот же курс, а не копия: пересчёт идёт после каждого изменения входов, и поднимать
        // редакцию на каждом было бы шумом в истории пунктов.
        assertSame(twoPacks, twoPacks.clamped(9.doses, plenty, LATER))
        assertSame(twoPacks, twoPacks.clamped(28.doses, plenty, LATER))
    }

    @Test
    fun trimmingNeverRaisesAnAllocation() {
        val small = activeCourse(sources = listOf(source(PACK, 1)))
        assertEquals(listOf(1.doses), small.clamped(28.doses, plenty, LATER).allocations())
    }

    @Test
    fun emptyMedicineSurvivesTheClamp() {
        val noPacks = activeCourse()
        assertSame(noPacks, noPacks.clamped(5.doses, plenty, LATER))
    }

    @Test
    fun orderAndPackagesAreUntouched() {
        val clamped = twoPacks.clamped(6.doses, plenty, LATER)
        assertEquals(listOf(PACK, OTHER_PACK), clamped.medicine.sources.map { it.pkg.id })
    }
}
