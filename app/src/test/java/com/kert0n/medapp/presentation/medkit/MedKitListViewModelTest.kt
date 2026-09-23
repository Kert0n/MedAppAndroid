package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя", location = "В ванной"))

    private fun viewModel(
        freshening: com.kert0n.medapp.feature.operation.Freshening =
            com.kert0n.medapp.fixture.offlineFreshening(com.kert0n.medapp.fixture.FakePackages(), clock)
    ) = MedKitListViewModel(freshening, medKits, Today(clock, QuietClock))

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

    /**
     * При связи список полок перечитывается, и пока сервер не ответил, экран ждёт: человек не должен
     * выбрать полку, из которой его вывели (PLAN E4). Спрошен только список, а не содержимое.
     */
    @Test
    fun theListWaitsForTheServerBeforeOfferingShelves() {
        val server = com.kert0n.medapp.fixture.RereadingServer(clock)
        server.hold()
        val model = viewModel(com.kert0n.medapp.fixture.onlineFreshening(server, com.kert0n.medapp.fixture.FakePackages(), clock))

        watching(model.state) { state ->
            kotlinx.coroutines.withTimeout(5_000) { while (server.asked.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(ScreenState.Loading, state.value)
            server.release()
            state.awaiting { it is ScreenState.Ready }
        }

        assertEquals(listOf("/v1/med-kits"), server.asked)
    }

    /**
     * База не прочиталась — испорченный файл, диск, отказавший на чтении, — и экран говорит, что
     * данные устройства не читаются, предлагая повторить, а приложение остаётся открытым (ТЗ 4.3).
     *
     * Красная проверка: сбой потока базы улетает из `viewModelScope` необработанным — на телефоне
     * это падение процесса, а список так и ждёт.
     */
    @Test
    fun aListThatCannotBeReadSaysSoAndTheAppStays() {
        val broken = object : com.kert0n.medapp.storage.medkit.MedKitStorageRepository by medKits {
            override fun observeAll(today: java.time.LocalDate) =
                kotlinx.coroutines.flow.flow<List<com.kert0n.medapp.domain.medkit.MedKitProjection>> { throw IllegalStateException("file is not a database") }
        }
        val escaped = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        val state = try {
            val model = MedKitListViewModel(
                com.kert0n.medapp.fixture.offlineFreshening(com.kert0n.medapp.fixture.FakePackages(), clock),
                broken,
                Today(clock, QuietClock)
            )
            watching(model.state) { it.awaiting(timeout = kotlin.time.Duration.parse("2s")) { s -> s !is ScreenState.Loading } }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }

        assertEquals("сбой улетел мимо экрана: $escaped", emptyList<Throwable>(), escaped)
        assertEquals(ScreenState.Failed(com.kert0n.medapp.domain.Unavailability.DEVICE_STORAGE), state)
    }
}
