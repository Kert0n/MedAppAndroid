package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Перенос упаковки (PLAN H3 №11): куда можно положить, что записывается и что видно, когда
 * нельзя. Как это нарисовано — `PackageTransferScreenTest`; что делает перенос с очередью —
 * проверки самого сценария.
 */
class PackageTransferViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    private val stored = FakePackages()

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participantCount = 2)
    )

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun viewModel(
        transactions: Transactions = DirectTransactions,
        lying: Package = pack(id = PACK, quantity = tablets("20"))
    ) = PackageTransferViewModel(
        relocation = PackageRelocation(stored, medKits, FakeCourses(), queue, transactions, clock),
        packages = stored,
        medKits = medKits,
        today = Today(clock, Quiet),
        packageId = PACK
    ).also { stored.lying(lying) }

    /**
     * Полка, где коробка уже лежит, не предлагается; общая предлагается наравне с местной —
     * аптечки доступны всегда, что при этом едет серверу, решает сценарий (C3).
     *
     * Красная проверка: спрятать общие — человек не найдёт свою дачу в списке и не поймёт почему.
     */
    @Test
    fun theShelfItLiesOnIsNotOfferedAndTheSharedOneIs() {
        val model = viewModel()

        val state = watching(model.state) { state -> state.awaiting { it.isLoaded } }

        assertEquals(listOf(SHARED_KIT), state.places.map { it.id })
        assertTrue(state.places.single().isShared)
    }

    /** Ничего не выбрано — ничего не записано: перенос «куда-нибудь» не бывает. */
    @Test
    fun nothingIsWrittenUntilSomewhereIsChosen() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.transfer()
            state.value
        }

        assertFalse(state.isDone)
        assertEquals(HOME_KIT, stored.packages.single().medKit.id)
    }

    /**
     * Перенос переставляет место и только его: остаток не трогается.
     *
     * Красная проверка: списать при переносе хоть сколько-нибудь — остаток изменится.
     */
    @Test
    fun aTransferMovesTheBoxAndSpendsNothing() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.choose(SHARED_KIT)
            model.transfer()
            state.awaiting { it.isDone }
        }

        assertTrue(state.isDone)
        assertEquals(SHARED_KIT, stored.packages.single().medKit.id)
        assertEquals(tablets("20"), stored.packages.single().quantity)
    }

    /** Аптечку убрали, пока человек выбирал: сказано, и записано ничего не будет. */
    @Test
    fun aTargetThatIsGoneIsSaidAndWritesNothing() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.choose(SHARED_KIT)
            medKits.forget(SHARED_KIT)
            state.awaiting { it.places.isEmpty() }
            model.transfer()
            state.awaiting { it.refusal != null }
        }

        assertEquals(PackageTransferRefusal.TARGET_GONE, state.refusal)
        assertFalse(state.isDone)
        assertEquals(HOME_KIT, stored.packages.single().medKit.id)
    }

    /** Полка, с которой несут, ждёт ответа на своё решение: сказано причиной, не тишиной. */
    @Test
    fun aBusyOriginRefusesInWords() {
        // Коробка знает свою полку ссылкой — и её решение вместе с ней (PLAN E5).
        val model = viewModel(lying = pack(id = PACK, medKit = medKit(id = HOME_KIT, status = MedKitStatus.REMOVING).ref))

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.choose(SHARED_KIT)
            model.transfer()
            state.awaiting { it.refusal != null }
        }

        assertEquals(PackageTransferRefusal.ORIGIN_BUSY, state.refusal)
        assertEquals(HOME_KIT, stored.packages.single().medKit.id)
    }

    /**
     * Второе нажатие не начинает второго дела.
     *
     * Красная проверка: снять замок — в дверь приходят двое.
     */
    @Test
    fun aSecondTapCallsTheScenarioOnce() {
        val held = HeldTransactions()
        val model = viewModel(transactions = held)

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.choose(SHARED_KIT)
            model.transfer()
            model.transfer()
            held.door.release()
            state.awaiting { it.isDone }
        }

        assertEquals(1, held.door.waiting)
        assertTrue(state.isDone)
    }

    /** Коробки не стало, пока экран открыт: сказано, переносить нечего. */
    @Test
    fun aGoneBoxIsSaid() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            stored.forget(PACK)
            state.awaiting { it.isGone }
        }

        assertTrue(state.isGone)
        assertFalse(state.isDone)
    }
}
