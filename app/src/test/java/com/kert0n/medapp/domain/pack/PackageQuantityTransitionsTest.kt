package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.value.Quantity

import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.ended
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.tablets

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Переходы, меняющие остаток. Пустой коробки не бывает: кончившаяся перестаёт существовать так же,
 * как выброшенная, и переход отвечает на это концом (PLAN D3). Истории у коробки нет: переход
 * меняет число или кончает коробку и ничего не объясняет (D7).
 */
class PackageQuantityTransitionsTest {

    @Test
    fun consumingToZeroEndsThePack() {
        val ending = pack(quantity = tablets("2")).consume(dose("2")).ended()
        assertEquals(tablets("2"), ending.pkg.quantity)
    }

    @Test
    fun consumingPartOfThePackKeepsIt() {
        val after = pack(quantity = tablets("20")).consume(dose("0.5"))
        assertEquals(tablets("19.5"), after.left().quantity)
    }

    @Test
    fun disposingMoreThanIsLeftDisposesOfEverything() {
        // Выбросил «пачку» из трёх таблеток, назвав пять: в минус не уходит, коробки нет.
        // Правило — переход пачки, а не хранения, которое его записывает.
        val ending = pack(quantity = tablets("3")).dispose(tablets("5")).ended()
        assertEquals(tablets("3"), ending.pkg.quantity)
    }

    @Test
    fun disposingPartOfThePackKeepsIt() {
        val after = pack(quantity = tablets("20")).dispose(tablets("2"))
        assertEquals(tablets("18"), after.left().quantity)
    }

    /** Выброшенная целиком коробка кончается, и её запись остаётся — за неё держатся приёмы. */
    @Test
    fun throwingThePackOutKeepsItsRecord() {
        val box = pack(quantity = tablets("20"))
        val ending = box.ended()
        assertEquals(box.record, ending.record)
        assertEquals(box, ending.pkg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun consumingMoreThanIsLeftIsRefused() {
        pack(quantity = tablets("3")).consume(dose("5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun consumingNothingIsNotAnIntake() {
        // Проверка переехала на саму дозу: нулевого расхода не бывает вовсе (D1).
        dose(Quantity.zero(TABLETS))
    }

    @Test
    fun recountToZeroEndsThePack() {
        val ending = pack(quantity = tablets("20")).correctTo(Quantity.zero(TABLETS)).ended()
        assertEquals(tablets("20"), ending.pkg.quantity)
    }

    @Test
    fun recountMayFindMoreThanWasKnown() {
        // Пересчёт — замена значения, а не дельта: пачку могли докупить или ошибиться в учёте.
        val after = pack(quantity = tablets("3")).correctTo(tablets("12"))
        assertEquals(tablets("12"), after.left().quantity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recountDoesNotChangeTheUnit() {
        pack(quantity = tablets("20")).correctTo(millilitres("20"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyPackCannotBeAssembled() {
        // Пустой коробки не бывает — ни новой, ни прочитанной из базы: кончившаяся удаляется.
        pack(quantity = Quantity.zero(TABLETS))
    }
}
