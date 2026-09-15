package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.Today
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Список аптечек (PLAN H3 №2): состояние приходит из базы, и что лежит внутри полки — часть её
 * строки, а не подпись к ней.
 */
class MedKitListViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private fun viewModel() = MedKitListViewModel(medKits, Today(clock))

    /**
     * Пока первое чтение не пришло, список не притворяется пустым: «пусто» и «ещё не прочитано» —
     * разные вещи, и человек делает в них разное.
     */
    @Test
    fun beforeTheFirstReadThereIsNothingToShowYet() {
        assertEquals(ScreenState.Loading, viewModel().state.value)
    }

    @Test
    fun theListCarriesWhatLiesInsideEachShelf() {
        medKits.contents[HOME_KIT] = MedKitContents(packages = 12, expired = 2)

        val shown = watching(viewModel().state) { it.shelves() }.single()

        assertEquals("Домашняя", shown.name)
        assertEquals(MedKitContents(12, 2), shown.contents)
    }

    /** Заведённая полка появляется в списке сама: экран ничего не перечитывает руками (PLAN H1). */
    @Test
    fun aShelfAddedWhileTheScreenIsOpenAppearsByItself() {
        val names = watching(viewModel().state) { state ->
            state.shelves()
            medKits.add(medKit(id = SHARED_KIT, name = "Дача"))
            state.awaiting { it.names().size == 2 }.names()
        }

        assertEquals(listOf("Домашняя", "Дача"), names)
    }

    /** Убранная полка со списка уходит. */
    @Test
    fun aShelfRemovedWhileTheScreenIsOpenLeavesTheList() {
        val names = watching(viewModel().state) { state ->
            state.shelves()
            medKits.forget(HOME_KIT)
            state.awaiting { it.names().isEmpty() }.names()
        }

        assertEquals(emptyList<String>(), names)
    }

    /** Дождаться прочитанного списка: до первого чтения экран показывает загрузку. */
    private suspend fun ScreenStateOfShelves.shelves(): List<MedKitPresentationDTO> =
        awaiting { it is ScreenState.Ready }.let { (it as ScreenState.Ready).value }

    private fun ScreenState<List<MedKitPresentationDTO>>.names(): List<String> =
        (this as? ScreenState.Ready)?.value?.map { it.name } ?: emptyList()
}

private typealias ScreenStateOfShelves = kotlinx.coroutines.flow.StateFlow<ScreenState<List<MedKitPresentationDTO>>>
