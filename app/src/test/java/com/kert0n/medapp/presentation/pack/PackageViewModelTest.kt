package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Карточка упаковки (PLAN H3 №6): удаление спрашивается, исход сценария виден, а исчезнувшая
 * коробка не притворяется загружающейся.
 */
class PackageViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages(pack(id = PACK, name = "Нурофен"))

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private fun viewModel(transactions: Transactions = DirectTransactions) = PackageViewModel(
        removal = PackageRemoval(packages, QueueService(DirectTransactions, FakeQueue()), transactions, clock),
        packages = packages,
        medKits = medKits,
        courses = FakeCourses(),
        today = Today(clock),
        savedState = SavedStateHandle(mapOf(RouteArguments.PACKAGE_ID to PACK.toString()))
    )

    @Test
    fun theCardShowsTheBoxAndTheShelfItLiesOn() {
        val state = watching(viewModel().state) { it.awaiting { s -> s.pack != null } }

        assertEquals("Нурофен", state.pack?.name)
        assertEquals("Домашняя", state.medKitName)
    }

    /**
     * Удаление необратимо: нажатие открывает разговор, а сценарий зовётся только после согласия.
     *
     * Красная проверка: звать сценарий из `askToRemove` — коробка исчезает без вопроса.
     */
    @Test
    fun removalAsksBeforeItRemoves() {
        val model = viewModel()

        val asked = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            state.awaiting { it.asksToRemove }
        }

        assertTrue(asked.asksToRemove)
        assertEquals(1, packages.packages.size)
    }

    @Test
    fun agreeingRemovesTheBoxAndLeavesTheCard() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.remove()
            state.awaiting { it.isRemoved }
        }

        assertTrue(state.isRemoved)
        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Отказ от разговора ничего не удаляет. */
    @Test
    fun dismissingTheQuestionRemovesNothing() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.dismissRemoval()
            state.awaiting { !it.asksToRemove }
        }

        assertEquals(1, packages.packages.size)
    }

    /** Без согласия сценарий не зовётся, даже если нажать «удалить» мимо разговора. */
    @Test
    fun removalWithoutTheQuestionDoesNothing() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.remove()
            state.awaiting { it.pack != null }
        }

        assertEquals(1, packages.packages.size)
    }

    /** Коробки не стало, пока карточка была открыта: это не загрузка, и экран говорит о ней. */
    @Test
    fun aBoxThatDisappearedIsNotALoadingCard() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            packages.forget(PACK)
            state.awaiting { it.isGone }
        }

        assertTrue(state.isGone)
        assertTrue(!state.isLoading)
    }

    /**
     * Пока удаление в пути, второго не начинается: разговор больше не открывается, и ответ на
     * второе не подменяет собой ответ на первое.
     *
     * Красная проверка: снять замок — второе решение уходит сценарию, и «удаление в пути»
     * сменяется на «коробка занята», хотя занята она этим же удалением.
     */
    @Test
    fun aSecondRemovalIsNotStartedWhileTheFirstIsOnItsWay() {
        val transactions = HeldTransactions()
        val model = viewModel(transactions)

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.remove()
            model.askToRemove()
            model.remove()
            transactions.door.release()
            state.awaiting { it.isRemoved }
        }

        assertEquals(1, transactions.door.waiting)
    }
}
