package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.PackageCodes
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
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Форма, открытая сканером (PLAN H3 «Набор сканера»). Сканер ничего не показывает от себя: он
 * предзаполняет обычный экран новой коробки, и дальше человек идёт привычным путём.
 */
class PackageFormScanTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages(pack(id = PACK))

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private val codes = FakePackageCodes()

    private val text = "0104601234567890215ABCDE12345\u001D91EE11"

    private fun viewModel(opened: PackageFormViewModel.Opened) = PackageFormViewModel(
        adding = PackageAdding(packages, medKits, queue, DirectTransactions, clock),
        describing = PackageDescribing(packages, FakeFollowing(), queue, DirectTransactions, clock),
        searching = TemplateSearching(FakePackageTemplates()),
        scanning = PackageScanning(codes),
        packages = packages,
        vocabulary = FakeVocabulary(),
        medKits = medKits,
        today = Today(clock, QuietClock),
        opened = opened
    )

    private fun scanned() = viewModel(PackageFormViewModel.Opened(medKitId = HOME_KIT, scannedCode = text))

    /**
     * Реестр спрашивают **один раз и той же строкой**, какую дал распознаватель: чужой API мы
     * бережём (PLAN H5), а разделители GS внутри кода значащие — «почистив» их, мы спросили бы о
     * другой коробке.
     */
    @Test
    fun theRegistryIsAskedOnceWithTheCodeAsItWasRead() {
        codes.answer = FakePackageCodes.found()
        val model = scanned()

        watching(model.state) { state -> state.awaiting { it.form.name.isNotBlank() } }

        assertEquals(listOf(DataMatrixCode(text)), codes.asked)
    }

    /**
     * Что реестр знает — то и стоит в полях, **всё**: название, производитель, страна, срок. Чему
     * поля нет — то в описании его же словами, и ничего не теряется по дороге: иначе человек
     * переписывает с коробки то, что приложение уже прочитало.
     */
    @Test
    fun whatTheRegistryKnowsIsAlreadyInTheFields() {
        codes.answer = FakePackageCodes.found()
        val model = scanned()

        val state = watching(model.state) { it.awaiting { seen -> seen.form.name.isNotBlank() } }

        assertEquals("Цетрин", state.form.name)
        assertEquals("Индия", state.form.country)
        assertEquals("Dr. Reddy\u2019s", state.form.manufacturer)
        assertEquals(
            "таблетки, покрытые плёночной оболочкой, цетиризин, 10 мг, 20 таблеток в 2 блистерах",
            state.form.description
        )
    }

    /**
     * Составное количество числом не становится: «20 таблеток в 2 блистерах» — два числа, и ни
     * одно не остаток коробки. Прочитанное живьём «30 шт» становится (`ScanSuggestionMapperTest`).
     */
    @Test
    fun aCompositeQuantityIsLeftToThePerson() {
        codes.answer = FakePackageCodes.found()
        val model = scanned()

        val state = watching(model.state) { it.awaiting { seen -> seen.form.name.isNotBlank() } }

        assertEquals("", state.form.amount)
        assertNull(state.form.unit)
    }

    /**
     * Ответ идёт по сети, и человек за это время уже мог начать печатать. Затри набранное — и
     * форма спорила бы с тем, кто держит коробку в руках.
     */
    @Test
    fun whatThePersonTypedIsNotOverwrittenByTheAnswer() {
        codes.answer = FakePackageCodes.found()
        codes.hold()
        val model = scanned()

        val state = watching(model.state) { state ->
            val opened = state.awaiting { it.medKits.isNotEmpty() }
            model.edit(opened.form.copy(name = "Цетиризин"))
            codes.release()
            state.awaiting { it.form.country.isNotBlank() }
        }

        assertEquals("Цетиризин", state.form.name)
    }

    /** «Не найдено» — обычный ответ, а не ошибка: форма остаётся пустой, и её заполняют руками. */
    @Test
    fun anUnknownCodeLeavesAnOrdinaryEmptyForm() {
        codes.answer = PackageCodes.Lookup.NotFound
        val model = scanned()

        val state = watching(model.state) { it.awaiting { seen -> seen.medKits.isNotEmpty() } }

        assertEquals("", state.form.name)
        assertNull("«не найдено» — не отказ формы", state.error)
    }

    /** Реестра нет — коробку всё равно заводят: форма работает и без него. */
    @Test
    fun anUnreachableRegistryDoesNotBreakTheForm() {
        codes.answer = PackageCodes.Lookup.Unavailable(Unavailability.NO_CONNECTION)
        val model = scanned()

        val state = watching(model.state) { it.awaiting { seen -> seen.medKits.isNotEmpty() } }

        assertEquals("", state.form.name)
        assertNull(state.error)
    }

    /** У правки кода нет, и спрашивать реестр ей не о чем. */
    @Test
    fun editingAPackageAsksTheRegistryNothing() {
        val model = viewModel(PackageFormViewModel.Opened(packageId = PACK))

        watching(model.state) { it.awaiting { seen -> seen.form.name.isNotBlank() } }

        assertTrue("реестр не спрошен", codes.asked.isEmpty())
    }
}
