package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Пересчёт (PLAN H3 №9): одно число, ноль не принимается, второе нажатие ничего не начинает. Как
 * это нарисовано — `PackageRecountScreenTest`; что делает переход на коробке — её проверки.
 */
class PackageRecountViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val stored = FakePackages()

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun viewModel(transactions: Transactions = DirectTransactions) = PackageRecountViewModel(
        adjusting = PackageAdjusting(stored, FakeFollowing(), queue, transactions, clock),
        vocabulary = FakeVocabulary(),
        packages = stored,
        packageId = PACK
    )

    private fun type(model: PackageRecountViewModel, amount: String) = model.edit(PackageRecountPresentationDTO(amount))

    /** Человек назвал число целиком — коробка стала такой, и экран уходит. */
    @Test
    fun theSeenNumberIsWrittenAsAWhole() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "17")
            model.submit()
            state.awaiting { it.isDone }
        }

        assertTrue(state.isDone)
        assertEquals(tablets("17"), stored.packages.single().quantity)
    }

    /**
     * Ноль не принимается: ноль — это «выбросить», и делается это с карточки (решение владельца).
     * Отказ так и говорит, и ничего не записано.
     *
     * Красная проверка: пропустить ноль к сценарию — коробка кончается пересчётом, мимо
     * подтверждения на карточке.
     */
    @Test
    fun zeroIsRefusedAndNothingIsWritten() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "0")
            model.submit()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageRecountError.Zero, state.error)
        assertFalse(state.isDone)
        assertEquals(tablets("20"), stored.packages.single().quantity)
    }

    /** Не число — отказ называет причину, ничего не записано; ввод снимает отказ. */
    @Test
    fun whatIsNotANumberIsRefusedAndTypingClearsTheRefusal() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val model = viewModel()

        val refused = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "семнадцать")
            model.submit()
            state.awaiting { it.error != null }
        }
        assertEquals(PackageRecountError.Amount(QuantityPresentationError.NOT_A_DECIMAL), refused.error)
        assertEquals(tablets("20"), stored.packages.single().quantity)

        val typed = watching(model.state) { state ->
            type(model, "17")
            state.awaiting { it.error == null }
        }
        assertNull(typed.error)
        assertEquals("17", typed.form.amount)
    }

    /**
     * Второе нажатие не начинает второго дела: замок ставится до `launch`, иначе второе нажатие
     * проходило бы, пока читается словарь.
     *
     * Красная проверка: снять замок — в дверь приходят двое, и пересчёт записывается дважды.
     */
    @Test
    fun aSecondTapCallsTheScenarioOnce() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val held = HeldTransactions()
        val model = viewModel(transactions = held)

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "17")
            model.submit()
            model.submit()
            held.door.release()
            state.awaiting { it.isDone }
        }

        assertEquals(1, held.door.waiting)
        assertTrue(state.isDone)
    }

    /** Напечатанное не затирается тем, что принесло чтение: форма — своё поле. */
    @Test
    fun whatIsTypedSurvivesANewReading() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "1")
            stored.lying(pack(id = PACK, quantity = tablets("19")))
            state.awaiting { it.pack?.effective?.amount == "19" }
        }

        assertEquals("1", state.form.amount)
    }

    /** Коробки не стало и коробка занята другим решением — сообщение, а не тишина. */
    @Test
    fun aGoneBoxIsSaidNotSwallowed() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "17")
            stored.forget(PACK)
            state.awaiting { it.isGone }
        }

        assertTrue(state.isGone)
        assertFalse(state.isDone)
    }

    @Test
    fun aBusyBoxRefusesAndNothingChanges() {
        stored.lying(pack(id = PACK, quantity = tablets("20")))
        runBlocking { stored.mark(PACK, PackageStatus.REMOVING, by = Uuid.random()) }
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            type(model, "17")
            model.submit()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageRecountError.Busy, state.error)
        assertFalse(state.isDone)
        assertEquals(tablets("20"), stored.packages.single().quantity)
    }
}
