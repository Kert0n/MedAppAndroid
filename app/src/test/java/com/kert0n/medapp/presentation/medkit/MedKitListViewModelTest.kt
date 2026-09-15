package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Список аптечек (PLAN H3 №2): человек видит не названия, а что внутри. Как это нарисовано,
 * проверяет `MedKitListScreenTest`.
 */
class MedKitListViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя", location = "В ванной"))

    private fun viewModel() = MedKitListViewModel(medKits, Today(clock, Quiet))

    /**
     * Пока база не ответила, экран ждёт, а не говорит «пусто».
     *
     * Красная проверка: начинать с готового пустого списка — человек читает «аптечек нет» на
     * мгновение раньше, чем они появятся, и предложение завести первую мигает зря.
     */
    @Test
    fun anUnreadListIsNotCalledEmpty() {
        assertEquals(ScreenState.Loading, viewModel().state.value)
    }

    /** Список приходит из базы вместе с тем, что внутри полки. */
    @Test
    fun theListTellsWhatIsInsideEachShelf() {
        medKits.contents[HOME_KIT] = MedKitContents(packages = 12, expired = 2)
        val model = viewModel()

        val state = watching(model.state) { it.awaiting { s -> s is ScreenState.Ready } }

        val shelf = (state as ScreenState.Ready).value.single()
        assertEquals("Домашняя", shelf.name)
        assertEquals("В ванной", shelf.location)
        assertEquals(MedKitContents(packages = 12, expired = 2), shelf.contents)
    }
}
