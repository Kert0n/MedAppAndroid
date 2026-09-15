package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldVocabulary
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Заведение и правка упаковки (PLAN H3 №7, №8): что записывается и что человек видит в ответ.
 */
class PackageFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    private val packages = FakePackages()

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun viewModel(
        opened: PackageFormViewModel.Opened = PackageFormViewModel.Opened(medKitId = HOME_KIT),
        vocabulary: VocabularyStorageRepository = FakeVocabulary()
    ) = PackageFormViewModel(
        adding = PackageAdding(packages, medKits, queue, DirectTransactions, clock),
        describing = PackageDescribing(packages, FakeFollowing(), queue, DirectTransactions, clock),
        packages = packages,
        vocabulary = vocabulary,
        medKits = medKits,
        today = Today(clock, Quiet),
        opened = opened
    )

    private fun filled(state: PackageFormUiState) = state.form.copy(
        name = "Нурофен",
        amount = "20",
        unit = TABLETS.toPresentationDTO()
    )

    @Test
    fun fourFieldsAreEnoughAndTheShelfIsTheOneWeCameFrom() {
        val model = viewModel()

        watching(model.state) { state ->
            val ready = state.awaiting { it.units.isNotEmpty() }
            model.edit(filled(ready))
            model.save()
            state.awaiting { it.saved != null }
        }

        val written = packages.packages.single()
        assertEquals("Нурофен", written.facts.name)
        assertEquals(tablets("20"), written.quantity)
        assertEquals(HOME_KIT, written.medKit.id)
    }

    /** Отказ называет своё поле: человек иначе ищет ошибку глазами по всей форме. */
    @Test
    fun aRefusalNamesItsOwnField() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.units.isNotEmpty() }
            model.save()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageFormError.NameEmpty, state.error)
        assertEquals(PackageFormError.Field.NAME, state.error?.field)
    }

    /** Ввод снимает отказ. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            val ready = state.awaiting { it.units.isNotEmpty() }
            model.save()
            state.awaiting { it.error != null }
            model.edit(filled(ready))
            state.awaiting { it.error == null }
        }

        assertEquals(null, state.error)
    }

    /** Правка открывается записанным и количество не трогает: у пересчёта свой след. */
    @Test
    fun editingOpensWithWhatIsWrittenAndDoesNotTouchTheAmount() {
        packages.lying(pack(id = PACK, name = "Нурофен", quantity = tablets("20")))
        val model = viewModel(PackageFormViewModel.Opened(packageId = PACK))

        val state = watching(model.state) { it.awaiting { s -> s.form.name.isNotEmpty() } }

        assertEquals("Нурофен", state.form.name)
        assertEquals(true, state.isEditing)
        assertEquals(tablets("20"), packages.packages.single().quantity)
    }

    /**
     * Второе нажатие, пока идёт первое, не заводит вторую коробку: между нажатием и записью
     * стоит чтение словаря, и окно ровно такой длины.
     *
     * Красная проверка: ставить замок внутри записи — коробок две.
     */
    @Test
    fun aSecondTapDuringTheFirstWritesNothingExtra() {
        val vocabulary = HeldVocabulary()
        val model = viewModel(vocabulary = vocabulary)

        watching(model.state) { state ->
            val ready = state.awaiting { it.units.isNotEmpty() }
            model.edit(filled(ready))
            model.save()
            model.save()
            vocabulary.door.release()
            state.awaiting { it.saved != null }
        }

        assertEquals(1, vocabulary.door.waiting)
        assertEquals(1, packages.packages.size)
    }
}
