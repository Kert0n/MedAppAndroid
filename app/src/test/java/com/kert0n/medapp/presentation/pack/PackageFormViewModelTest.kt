package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.scan.PackageScanning
import com.kert0n.medapp.feature.template.TemplateSearching
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackageCodes
import com.kert0n.medapp.fixture.FakePackageTemplates
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldVocabulary
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.network.pack.PackageSyncState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Заведение и правка упаковки (PLAN H3 №7, №8): что записывается и что человек видит в ответ.
 */
class PackageFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages()

    /** Реестр кодов: обычной форме он не нужен вовсе, и спрашивать его она не должна. */
    private val codes = FakePackageCodes()

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun viewModel(
        opened: PackageFormViewModel.Opened = PackageFormViewModel.Opened(medKitId = HOME_KIT),
        vocabulary: VocabularyStorageRepository = FakeVocabulary(),
        packages: PackageStorageRepository = this.packages
    ) = PackageFormViewModel(
        adding = PackageAdding(packages, medKits, queue, DirectTransactions, clock),
        describing = PackageDescribing(packages, FakeFollowing(), queue, DirectTransactions, clock),
        searching = TemplateSearching(FakePackageTemplates()),
        scanning = PackageScanning(codes),
        packages = packages,
        vocabulary = vocabulary,
        medKits = medKits,
        today = Today(clock, QuietClock),
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

    /**
     * Запись, которая не удалась по причине вне приложения, — полный диск, испорченная база, —
     * не закрывает приложение и не оставляет форму вечно «сохраняющейся»: введённое остаётся на
     * месте, и нажать «Сохранить» можно снова (ТЗ 4.3).
     *
     * Красная проверка: сбой сценария улетает из `viewModelScope` необработанным — на телефоне
     * это падение процесса, а форма так и остаётся в `isSaving`.
     */
    @Test
    fun aWriteThatFailsLeavesTheFormToTryAgain() {
        val failing = object : PackageStorageRepository by packages {
            override suspend fun add(pkg: Package, sync: PackageSyncState) =
                throw IllegalStateException("database or disk is full")
        }
        val escaped = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        val state = try {
            val model = viewModel(packages = failing)
            watching(model.state) { state ->
                val ready = state.awaiting { it.units.isNotEmpty() }
                model.edit(filled(ready))
                model.save()
                state.awaiting(timeout = 2.seconds) { !it.isSaving }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }

        assertTrue("сбой улетел мимо экрана: $escaped", escaped.isEmpty())
        assertEquals(null, state.saved)
        assertEquals("Нурофен", state.form.name)
        assertTrue(packages.packages.isEmpty())
    }
}
