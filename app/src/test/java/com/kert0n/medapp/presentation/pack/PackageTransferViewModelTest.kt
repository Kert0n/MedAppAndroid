package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Перенос упаковки (PLAN H3 №11): место меняется, остаток — нет.
 */
class PackageTransferViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages().lying(pack(id = PACK, quantity = tablets("20")))

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача")
    )

    private fun viewModel() = PackageTransferViewModel(
        relocation = PackageRelocation(
            packages = packages,
            medKits = medKits,
            courses = FakeCourses(),
            queue = QueueService(DirectTransactions, FakeQueue()),
            transactions = DirectTransactions,
            clock = clock
        ),
        packages = packages,
        medKits = medKits,
        today = Today(clock),
        savedState = SavedStateHandle(mapOf(RouteArguments.PACKAGE_ID to PACK.toString()))
    )

    /**
     * Аптечка, где коробка уже лежит, не предлагается: положить её туда, где она лежит, нечем.
     *
     * Красная проверка: показать все полки — в списке появится та, из которой переносят.
     */
    @Test
    fun theShelfItAlreadyLiesOnIsNotOffered() {
        val state = watching(viewModel().state) { it.awaiting { s -> s.places.isNotEmpty() } }

        assertEquals(listOf("Дача"), state.places.map { it.name })
    }

    /** Перенос двигает коробку и ничего не расходует. */
    @Test
    fun aTransferMovesTheBoxAndSpendsNothing() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.places.isNotEmpty() }
            model.choose(SHARED_KIT)
            model.transfer()
            state.awaiting { it.isDone }
        }

        val moved = packages.packages.single()
        assertEquals(SHARED_KIT, moved.medKit.id)
        assertEquals(tablets("20"), moved.quantity)
    }

    /** Пока место не выбрано, не пишется ничего. */
    @Test
    fun withoutAChosenPlaceNothingIsWritten() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.places.isNotEmpty() }
            model.transfer()
            state.awaiting { !it.isDone }
        }

        assertEquals(HOME_KIT, packages.packages.single().medKit.id)
    }

    /** Исчезнувшая цель ничего не меняет, и человеку сказано выбрать другую. */
    @Test
    fun aTargetThatIsGoneChangesNothing() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.places.isNotEmpty() }
            model.choose(SHARED_KIT)
            medKits.forget(SHARED_KIT)
            model.transfer()
            state.awaiting { it.refusal != null }
        }

        assertEquals(TransferRefusal.TARGET_GONE, state.refusal)
        assertEquals(HOME_KIT, packages.packages.single().medKit.id)
    }

    /** Переносить некуда — это состояние экрана, а не пустой список без объяснения. */
    @Test
    fun withNowhereToPutItTheScreenHasNothingToOffer() {
        medKits.forget(SHARED_KIT)

        val state = watching(viewModel().state) { it.awaiting { s -> !s.isLoading } }

        assertTrue(state.places.isEmpty())
    }

    /** Выбор места снимает прежний отказ: человек уже делает то, о чём его просили. */
    @Test
    fun choosingAgainClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.places.isNotEmpty() }
            model.choose(SHARED_KIT)
            medKits.forget(SHARED_KIT)
            model.transfer()
            state.awaiting { it.refusal != null }
            medKits.add(medKit(id = SHARED_KIT, name = "Дача"))
            model.choose(SHARED_KIT)
            state.awaiting { it.refusal == null }
        }

        assertEquals(null, state.refusal)
    }
}
