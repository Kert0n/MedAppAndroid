package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.stock.StockMovement
import com.kert0n.medapp.domain.value.Quantity

import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.ended
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.tablets

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Переходы, меняющие остаток. Пустой коробки не бывает: кончившаяся перестаёт существовать так же,
 * как выброшенная, и переход отвечает на это концом (PLAN D3). Конец несёт свой след, и выбирает
 * его переход: у расхода следа нет — приём и есть учётная запись о нём, — а у всего остального
 * есть, иначе остаток пропал бы из учёта без объяснения (PLAN D7, H6).
 */
class PackageQuantityTransitionsTest {

    private val movementId: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")

    @Test
    fun consumingToZeroEndsThePack() {
        val ending = pack(quantity = tablets("2")).consume(dose("2")).ended()
        // Расход объясняет себя сам: движения у него нет, «истрачено» читается по приёмам (H6).
        assertNull(ending.trace)
    }

    @Test
    fun consumingPartOfThePackKeepsIt() {
        val after = pack(quantity = tablets("20")).consume(dose("0.5"))
        assertEquals(tablets("19.5"), after.left().quantity)
        assertNull(after.trace)
    }

    @Test
    fun disposingMoreThanIsLeftDisposesOfEverything() {
        // Выбросил «пачку» из трёх таблеток, назвав пять: в минус не уходит, ушло три, коробки
        // нет. Правило — переход пачки, а не хранения, которое его записывает.
        val ending = pack(quantity = tablets("3")).dispose(tablets("5"), movementId, LATER).ended()
        assertEquals(tablets("3"), (ending.trace as StockMovement.Disposal).amount)
    }

    @Test
    fun disposingPartOfThePackKeepsItAndSaysWhatWentAway() {
        val after = pack(quantity = tablets("20")).dispose(tablets("2"), movementId, LATER)
        assertEquals(tablets("18"), after.left().quantity)
        assertEquals(tablets("2"), (after.trace as StockMovement.Disposal).amount)
    }

    /**
     * Выброшенная целиком коробка — та же утилизация: без её следа «истрачено за период» не
     * сошлось бы, а остаток исчез бы, никем не принятый и ничем не объяснённый (PLAN H6).
     */
    @Test
    fun throwingThePackOutExplainsWhereItsStockWent() {
        val ending = pack(quantity = tablets("20")).thrownOut(movementId, LATER)
        val disposal = ending.trace as StockMovement.Disposal
        assertEquals(tablets("20"), disposal.amount)
        assertEquals(movementId, disposal.id)
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
        val ending = pack(quantity = tablets("20")).correctTo(Quantity.zero(TABLETS), movementId, LATER).ended()
        val recount = ending.trace as StockMovement.Recount
        assertEquals(tablets("20"), recount.before)
        assertEquals(Quantity.zero(TABLETS), recount.after)
    }

    @Test
    fun recountMayFindMoreThanWasKnown() {
        // Пересчёт — замена значения, а не дельта: пачку могли докупить или ошибиться в учёте.
        val after = pack(quantity = tablets("3")).correctTo(tablets("12"), movementId, LATER)
        assertEquals(tablets("12"), after.left().quantity)
        assertEquals(tablets("3"), (after.trace as StockMovement.Recount).before)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recountDoesNotChangeTheUnit() {
        pack(quantity = tablets("20")).correctTo(millilitres("20"), movementId, LATER)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyPackCannotBeAssembled() {
        // Пустой коробки не бывает — ни новой, ни прочитанной из базы: кончившаяся удаляется.
        pack(quantity = Quantity.zero(TABLETS))
    }

    /**
     * Сервер назвал 12, а мы объясняем 17: разница −5 — чужое изменение, и в историю идёт только она
     * (PLAN D7). Объяснённое целиком следа не оставляет, а в разных единицах разницы нет вовсе.
     */
    @Test
    fun aDifferenceWeDoNotExplainIsARemoteChange() {
        val box = pack(quantity = tablets("20"))

        val change = box.changedElsewhere(tablets("12"), explained = tablets("17"), movementId, LATER)

        assertEquals(java.math.BigDecimal("-5"), change?.delta?.stripTrailingZeros())
        assertEquals(TABLETS, change?.unit)
        assertNull(change?.occurredAt)
        assertNull(box.changedElsewhere(tablets("17.000"), explained = tablets("17"), movementId, LATER))
        assertNull(box.changedElsewhere(millilitres("12"), explained = tablets("17"), movementId, LATER))
    }
}
