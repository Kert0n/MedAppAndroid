package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.feature.medkits.MedKitKeeping
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Создание и правка аптечки (PLAN H3 №3): что записывается и что человек видит в ответ. Как это
 * нарисовано, проверяет `MedKitFormScreenTest` — форма об этом не знает.
 */
class MedKitFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    /** Часы никто не переводил: день этой проверке важен только как дата чтения. */
    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private fun viewModel(medKitId: Uuid? = null, transactions: Transactions = DirectTransactions) =
        MedKitFormViewModel(
            keeping = MedKitKeeping(medKits, transactions, clock),
            medKits = medKits,
            today = Today(clock, Quiet),
            medKitId = medKitId
        )

    @Test
    fun aNewShelfIsWrittenWithWhatWasTyped() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO("Дача", "Летний домик"))
            model.save()
            state.awaiting { it is MedKitFormUiState.Editing && it.isSaved }
        }

        val added = medKits.medKits.single { it.name == "Дача" }
        assertEquals("Летний домик", added.location)
    }

    /** Правка открывается записанным, а не пустой формой. */
    @Test
    fun editingOpensWithWhatIsWritten() {
        val model = viewModel(medKitId = HOME_KIT)

        val state = watching(model.state) { it.awaiting { s -> s is MedKitFormUiState.Editing } }

        assertEquals(MedKitFormPresentationDTO("Домашняя", ""), (state as MedKitFormUiState.Editing).form)
    }

    /**
     * Полки нет — отказ, а не пустая форма.
     *
     * Красная проверка: показать пустую форму — человек заполнит её и не поймёт, куда делась
     * его правка (наследство разбора #16).
     */
    @Test
    fun aShelfThatIsNotThereIsRefusedRatherThanShownEmpty() {
        val model = viewModel(medKitId = Uuid.random())

        val state = watching(model.state) { it.awaiting { s -> s !is MedKitFormUiState.Loading } }

        assertEquals(MedKitFormUiState.Gone, state)
    }

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.save()
            state.awaiting { it is MedKitFormUiState.Editing && it.error != null }
            model.edit(MedKitFormPresentationDTO("Дача"))
            state.awaiting { it is MedKitFormUiState.Editing && it.error == null }
        }

        assertEquals(null, (state as MedKitFormUiState.Editing).error)
    }

    /**
     * Второе нажатие «Сохранить», пока идёт первое, не заводит вторую полку: человек нажимает
     * ещё раз, не дождавшись ответа, и ждёт от этого того же самого.
     *
     * Красная проверка: снять замок — записей две.
     */
    @Test
    fun aSecondTapDuringTheFirstWritesNothingExtra() {
        val transactions = HeldTransactions()
        val model = viewModel(transactions = transactions)

        watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO("Дача"))
            model.save()
            model.save()
            transactions.door.release()
            state.awaiting { it is MedKitFormUiState.Editing && it.isSaved }
        }

        assertEquals(1, transactions.door.waiting)
        assertEquals(1, medKits.medKits.count { it.name == "Дача" })
    }
}
