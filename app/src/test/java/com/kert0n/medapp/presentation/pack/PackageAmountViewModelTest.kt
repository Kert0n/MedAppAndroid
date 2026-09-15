package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Пересчёт и утилизация (PLAN H3 №9): одно наблюдение, два объяснения — и от объяснения зависит,
 * что значит названное число.
 */
class PackageAmountViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages().lying(pack(id = PACK, quantity = tablets("20")))

    private fun viewModel() = PackageAmountViewModel(
        adjusting = PackageAdjusting(
            packages = packages,
            following = FakeFollowing(),
            queue = QueueService(DirectTransactions, FakeQueue()),
            transactions = DirectTransactions,
            clock = clock
        ),
        vocabulary = FakeVocabulary(),
        packages = packages,
        savedState = SavedStateHandle(mapOf(RouteArguments.PACKAGE_ID to PACK.toString()))
    )

    private fun type(amount: String, change: AmountChange = AmountChange.RECOUNT) =
        PackageAmountPresentationDTO(change, amount)

    /** Пересчёт называет новое число целиком, а не разницу. */
    @Test
    fun aRecountNamesTheWholeNumber() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("17"))
            model.submit()
            state.awaiting { it.isDone }
        }

        assertEquals(tablets("17"), packages.packages.single().quantity)
    }

    /** Утилизация называет то, сколько ушло; остальное остаётся. */
    @Test
    fun aDisposalNamesWhatIsGone() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("3", AmountChange.DISPOSAL))
            model.submit()
            state.awaiting { it.isDone }
        }

        assertEquals(tablets("17"), packages.packages.single().quantity)
    }

    /**
     * Уходящее в ноль спрашивается: коробки после этого не будет, и это решение человека.
     *
     * Красная проверка: записывать ноль сразу — коробка исчезает без вопроса.
     */
    @Test
    fun goingToZeroAsksFirst() {
        val model = viewModel()

        val asked = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("0"))
            model.submit()
            state.awaiting { it.asksToEmpty }
        }

        assertTrue(asked.asksToEmpty)
        assertEquals(1, packages.packages.size)
    }

    @Test
    fun agreeingEndsTheBox() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("0"))
            model.submit()
            state.awaiting { it.asksToEmpty }
            model.confirmEmptying()
            state.awaiting { it.isDone }
        }

        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Отказ от вопроса ничего не пишет. */
    @Test
    fun refusingTheQuestionWritesNothing() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("0"))
            model.submit()
            state.awaiting { it.asksToEmpty }
            model.dismissEmptying()
            state.awaiting { !it.asksToEmpty }
        }

        assertEquals(tablets("20"), packages.packages.single().quantity)
    }

    /** Выбросить всё — тоже конец коробки, и он тоже спрашивается. */
    @Test
    fun disposingEverythingAsksToo() {
        val model = viewModel()

        val asked = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("20", AmountChange.DISPOSAL))
            model.submit()
            state.awaiting { it.asksToEmpty }
        }

        assertTrue(asked.asksToEmpty)
    }

    /** Выбросить ноль — это ничего не сделать. */
    @Test
    fun disposingZeroIsNotAnAction() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("0", AmountChange.DISPOSAL))
            model.submit()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageAmountError.NothingToDispose, state.error)
    }

    /**
     * Выбросить больше, чем лежало, — ошибка человека, а не повод списать до нуля.
     *
     * Красная проверка: отдать это домену — он вычтет без ухода в минус, и «выбросил 30»
     * молча станет «выбросил 20».
     */
    @Test
    fun disposingMoreThanThereIsIsRefused() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("30", AmountChange.DISPOSAL))
            model.submit()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageAmountError.MoreThanThereIs, state.error)
        assertEquals(tablets("20"), packages.packages.single().quantity)
    }

    /** Не число — отказ, а не падение. */
    @Test
    fun somethingThatIsNotANumberIsRefused() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type("семнадцать"))
            model.submit()
            state.awaiting { it.error != null }
        }

        assertEquals(
            PackageAmountError.Amount(QuantityPresentationError.NOT_A_DECIMAL),
            state.error
        )
    }

    /** Ввод снимает отказ. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.edit(type(""))
            model.submit()
            state.awaiting { it.error != null }
            model.edit(type("17"))
            state.awaiting { it.error == null }
        }

        assertEquals(null, state.error)
    }
}
