package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.tablets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Незакрытые команды ложатся на подтверждённый остаток по правилу E1; домен получает только
 * количество, а признаки очереди остаются в данных.
 */
class PackageQueueStateTest {

    private val paracetamol = PackageSharedFacts(name = "Парацетамол", form = TABLET_FORM)

    private fun consume(amount: String) = PackageSyncCommand.Consume(PACK, dose(amount), INTAKE)

    @Test
    fun confirmedAmountPassesThroughUntouched() {
        // В локальной аптечке исходящих команд нет вовсе (PLAN E1).
        val state = PackageQueueState(pack(quantity = tablets("20")))
        assertEquals(tablets("20"), state.amount)
        assertFalse(state.hasUnconfirmedChanges)
    }

    @Test
    fun consumptionIsSubtractedOnceAndLeavesTheNumberUnconfirmed() {
        val state = PackageQueueState(pack(quantity = tablets("20")), listOf(consume("3")))
        assertEquals(tablets("17"), state.amount)
        assertTrue(state.hasUnconfirmedChanges)
    }

    @Test
    fun queueMarksStayOutOfTheDomainAmount() {
        // Семнадцать подтверждённых и семнадцать после незакрытого расхода — для домена одно и то
        // же количество; разницу знает только очередь.
        val settled = PackageQueueState(pack(quantity = tablets("17")))
        val pending = PackageQueueState(pack(quantity = tablets("20")), listOf(consume("3")))
        assertEquals(settled.amount, pending.amount)
        assertNotEquals(settled.hasUnconfirmedChanges, pending.hasUnconfirmedChanges)
    }

    @Test
    fun changingTheListAfterwardsDoesNotChangeTheProjection() {
        // Свёртка считается при сборке: без своей копии добавленное позже удаление осталось бы
        // невидимым, и остаток показывал бы двадцать при уже назначенном нуле.
        val commands: MutableList<PackageSyncCommand> = mutableListOf(consume("3"))
        val state = PackageQueueState(pack(quantity = tablets("20")), commands)
        commands += PackageSyncCommand.Delete(PACK)
        assertEquals(tablets("17"), state.amount)
        assertEquals(1, state.unclosed.size)
    }

    @Test
    fun commandsOfAnotherPackAreNotFoldedIn() {
        // Чужая команда дала бы неверный остаток молча: состояние теперь знает, чьё оно.
        assertThrows(IllegalArgumentException::class.java) {
            PackageQueueState(
                pack(quantity = tablets("20")),
                listOf(PackageSyncCommand.Consume(OTHER_PACK, dose("3"), INTAKE))
            )
        }
    }

    /**
     * Единицу пачки сменил сосед на сервере, а команда в старой единице ещё не закрыта: подготовка
     * отвергнет её при взятии, а до того свёртка обязана остаться тотальной — число равно
     * подтверждённому, команда названа среди несовместимых, и чтение не бросает (PLAN E1).
     */
    @Test
    fun aCommandInAnotherUnitIsLeftOutOfTheNumberAndNamed() {
        val stale = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("30"))
        val state = PackageQueueState(pack(quantity = millilitres("100")), listOf(stale, consume("3")))

        assertEquals(millilitres("100"), state.amount)
        assertFalse(state.hasUnconfirmedChanges)
        assertEquals(listOf<PackageSyncCommand>(stale, consume("3")), state.incompatible)
    }

    @Test
    fun recountLaysItsDifferenceOverTheNumberBeforeIt() {
        // Человек видел 17 (20 без трёх, ещё не уехавших) и насчитал 30: разница +13 ложится
        // поверх того, что было к этому моменту, — и может оказаться больше прежнего.
        val state = PackageQueueState(
            pack(quantity = tablets("20")),
            unclosed = listOf(consume("3"), PackageSyncCommand.CorrectStock(PACK, seen = tablets("17"), actual = tablets("30")))
        )
        assertEquals(tablets("30"), state.amount)
    }

    @Test
    fun aRecountBelowZeroShowsZero() {
        // Видел 20, назвал 5, а сервер тем временем подтвердил 10: −15 поверх 10 — ноль на экране,
        // а отказ такому пересчёту даст подготовка (C1).
        val state = PackageQueueState(
            pack(quantity = tablets("10")),
            unclosed = listOf(PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("5")))
        )
        assertEquals(tablets("0"), state.amount)
        assertTrue(state.hasUnconfirmedChanges)
    }

    @Test
    fun deletionProjectsZero() {
        val state = PackageQueueState(
            pack(quantity = tablets("20")), listOf(PackageSyncCommand.Delete(PACK)))
        assertEquals(tablets("0"), state.amount)
    }

    @Test
    fun descriptiveCommandDoesNotMakeTheNumberUnconfirmed() {
        val state = PackageQueueState(
            pack(quantity = tablets("20")),
            unclosed = listOf(
                PackageSyncCommand.Describe(PACK, paracetamol, paracetamol.copy(country = "Чехия")),
                PackageSyncCommand.ReleaseClaim(PACK)
            )
        )
        assertEquals(tablets("20"), state.amount)
        assertFalse(state.hasUnconfirmedChanges)
    }

    @Test
    fun negativeProjectionIsShownAsZeroAndNotAsASuccessfulConsumption() {
        // Нехватка — конфликт операции, и разбирается она по состоянию очереди.
        val state = PackageQueueState(
            pack(quantity = tablets("2")), listOf(consume("5")))
        assertEquals(tablets("0"), state.amount)
    }

    @Test
    fun aCommandStillOnItsWayIsAlreadyInTheNumber() {
        // Расход, который ещё не доехал, устройство отправило само и знает, что отправило:
        // число есть всегда, а истину потом читает снимок (PLAN E1).
        val state = PackageQueueState(
            pack(quantity = tablets("20")),
            unclosed = listOf(consume("3"))
        )
        assertEquals(tablets("17"), state.amount)
        assertTrue(state.hasUnconfirmedChanges)
    }
}
