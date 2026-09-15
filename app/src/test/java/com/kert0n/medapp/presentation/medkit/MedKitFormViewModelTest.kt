package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.feature.medkits.MedKitKeeping
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Форма аптечки (PLAN H3 №3): что она пишет, чего не пишет и что показывает, когда сценарий
 * отказал.
 */
class MedKitFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя", location = "Ванная"))

    private val keeping = MedKitKeeping(medKits, DirectTransactions, clock)

    private fun viewModel(medKitId: Uuid? = null) = MedKitFormViewModel(
        keeping = keeping,
        medKits = medKits,
        today = Today(clock),
        savedState = SavedStateHandle(
            medKitId?.let { mapOf(RouteArguments.MED_KIT_ID to it.toString()) } ?: emptyMap()
        )
    )

    @Test
    fun aNewShelfIsWrittenWithWhatWasTyped() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO(name = "Дача", location = "Сарай"))
            model.save()
            state.awaiting { it.editing()?.isSaved == true }
        }

        val written = medKits.medKits.single { it.name == "Дача" }
        assertEquals("Сарай", written.location)
        assertEquals(MedKit.Publication.LOCAL, written.publication)
    }

    /**
     * Двойное нажатие «Сохранить» заводит одну полку, а не две: человек нажимает второй раз,
     * когда первый ответ ещё не пришёл, и ждёт от этого того же самого.
     *
     * Красная проверка: убрать сторожа идущей записи — полок становится две.
     */
    @Test
    fun pressingSaveTwiceWritesOneShelf() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO(name = "Дача"))
            model.save()
            model.save()
            state.awaiting { it.editing()?.isSaved == true }
        }

        assertEquals(1, medKits.medKits.count { it.name == "Дача" })
    }

    /** Невалидная форма ничего не пишет и остаётся открытой: закрыть её значило бы потерять ввод. */
    @Test
    fun anInvalidFormWritesNothingAndStaysOpen() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO(name = "   "))
            model.save()
            state.awaiting { it.editing()?.error != null }
        }

        assertEquals(MedKitFormError.Input.NAME_EMPTY, state.editing()?.error)
        assertEquals(1, medKits.medKits.size)
    }

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(MedKitFormPresentationDTO(name = ""))
            model.save()
            state.awaiting { it.editing()?.error != null }
            model.edit(MedKitFormPresentationDTO(name = "Д"))
            state.awaiting { it.editing()?.error == null }
        }

        assertNull(state.editing()?.error)
    }

    /** Открытая на правку форма показывает записанное. */
    @Test
    fun aFormOpenedForEditingShowsWhatIsStored() {
        val state = watching(viewModel(HOME_KIT).state) { it.awaiting { state -> state.editing() != null } }

        assertEquals(MedKitFormPresentationDTO("Домашняя", "Ванная"), state.editing()?.form)
        assertTrue(state.editing()?.isEditing == true)
    }

    /**
     * Полки, которой нет, — отказ, а не пустая форма: заполненную пустую человек сохранил бы и
     * завёл бы вместо правки вторую полку (наследство разбора #16).
     *
     * Красная проверка: открыть форму пустой при отсутствии полки — проверка краснеет.
     */
    @Test
    fun openingAShelfThatIsGoneIsARefusal() {
        val state = watching(viewModel(Uuid.random()).state) { it.awaiting { s -> s != MedKitFormUiState.Loading } }

        assertEquals(MedKitFormUiState.Gone, state)
    }

    /** Правка меняет только названное: место хранения не трогали — оно прежнее. */
    @Test
    fun editingChangesOnlyWhatWasNamed() {
        val model = viewModel(HOME_KIT)

        watching(model.state) { state ->
            val opened = state.awaiting { it.editing() != null }.editing()!!.form
            model.edit(opened.copy(name = "Домашняя аптечка"))
            model.save()
            state.awaiting { it.editing()?.isSaved == true }
        }

        val stored = medKits.medKits.single()
        assertEquals("Домашняя аптечка", stored.name)
        assertEquals("Ванная", stored.location)
    }

    /**
     * Полка, ждущая ответа сервера на свою уборку, не правится — и это сказано человеку, а не
     * проглочено (PLAN E1).
     *
     * Красная проверка: проглотить исход `BUSY` — форма молча делает вид, что записала.
     */
    @Test
    fun aShelfAwaitingItsRemovalRefusesTheEditOutLoud() {
        val busy = FakeMedKits(medKit(id = HOME_KIT).markRemoving())
        val model = MedKitFormViewModel(
            keeping = MedKitKeeping(busy, DirectTransactions, clock),
            medKits = busy,
            today = Today(clock),
            savedState = SavedStateHandle(mapOf(RouteArguments.MED_KIT_ID to HOME_KIT.toString()))
        )

        val state = watching(model.state) { state ->
            state.awaiting { it.editing() != null }
            model.edit(MedKitFormPresentationDTO(name = "Другое имя"))
            model.save()
            state.awaiting { it.editing()?.error != null }
        }

        assertEquals(MedKitFormError.Busy, state.editing()?.error)
        assertEquals(MedKitStatus.REMOVING, busy.medKits.single().status)
        assertEquals("Домашняя", busy.medKits.single().name)
    }

    private fun MedKitFormUiState.editing(): MedKitFormUiState.Editing? = this as? MedKitFormUiState.Editing
}
