package com.kert0n.medapp.feature.packs

import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.storage.pack.PackageQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Содержимое аптечки и все лекарства сразу (PLAN H3 №4 и №5). Запрос — три независимых поля, а не
 * история нажатий: искать и потом фильтровать это то же самое, что фильтровать и потом искать
 * (PLAN H4).
 */
class MedKitContentsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)

    private val packages = FakePackages(
        pack(id = PACK, name = "Парацетамол", category = "Жаропонижающие", form = TABLET_FORM),
        pack(
            id = OTHER_PACK,
            name = "Нурофен",
            medKit = medKit(id = SHARED_KIT).ref,
            category = "Обезболивающие"
        )
    )

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача")
    )

    private fun TestScope.viewModel(medKitId: Uuid? = HOME_KIT): MedKitContentsViewModel {
        val viewModel = MedKitContentsViewModel(
            packages = packages,
            medKits = medKits,
            removal = MedKitRemoval(medKits, packages, DirectTransactions),
            clock = clock
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.open(medKitId)
        return viewModel
    }

    /**
     * Красная проверка: складывать нажатия в историю — «искал, потом фильтровал» даст не то же
     * самое, что «фильтровал, потом искал», и случай краснеет.
     */
    @Test
    fun theOrderOfTapsDoesNotChangeTheResult() = runTest {
        val one = viewModel()
        one.search("пара")
        one.filter(PackageQuery.Filter.Expired)
        one.sort(PackageQuery.Sort.EXPIRY)

        val other = viewModel()
        other.sort(PackageQuery.Sort.EXPIRY)
        other.filter(PackageQuery.Filter.Expired)
        other.search("пара")

        assertEquals(one.state.value.query, other.state.value.query)
        assertEquals(one.state.value.packs, other.state.value.packs)
    }

    /** Поиск в аптечке остаётся в ней: область — поле запроса, а не отдельный экран (REQ-029). */
    @Test
    fun searchingInsideAMedKitStaysInsideIt() = runTest {
        val contents = viewModel()

        contents.search("о")

        assertEquals(HOME_KIT, contents.state.value.query.medKitId)
        assertEquals(listOf("Парацетамол"), contents.state.value.packs?.map { it.name })
    }

    /** Тот же поиск без названной аптечки ищет везде — это экран 5. */
    @Test
    fun searchingEverywhereFindsWhatLiesInOtherMedKits() = runTest {
        val everywhere = viewModel(medKitId = null)

        everywhere.search("о")

        assertTrue(everywhere.state.value.everywhere)
        assertEquals(
            listOf("Нурофен", "Парацетамол"),
            everywhere.state.value.packs?.map { it.name }?.sorted()
        )
    }

    /** Одинаковые названия из разных аптечек различает только имя аптечки — и оно есть. */
    @Test
    fun everywhereEachPackageSaysWhereItLies() = runTest {
        val everywhere = viewModel(medKitId = null)

        assertEquals("Домашняя", everywhere.state.value.medKitNames[HOME_KIT])
        assertEquals("Дача", everywhere.state.value.medKitNames[SHARED_KIT])
    }

    /** В своей аптечке название аптечки не повторяется: оно уже в заголовке. */
    @Test
    fun insideOneMedKitItsNameIsNotRepeatedOnEveryRow() = runTest {
        val contents = viewModel()

        assertTrue(contents.state.value.medKitNames.isEmpty())
        assertEquals("Домашняя", contents.state.value.medKit?.name)
    }

    /**
     * Выбирать категорию можно из всего, что в области есть, а не из того, что осталось после
     * выбора: иначе вернуться к другой категории было бы нечем.
     */
    @Test
    fun theChoicesDoNotShrinkAfterChoosing() = runTest {
        val everywhere = viewModel(medKitId = null)

        everywhere.filter(PackageQuery.Filter.OfCategory("Жаропонижающие"))

        assertEquals(
            listOf("Жаропонижающие", "Обезболивающие"),
            everywhere.state.value.categories
        )
        assertEquals(listOf("таблетки"), everywhere.state.value.forms.map { it.name })
    }

    /** «Здесь ничего нет» и «ничего не нашлось» — разные сообщения, и различает их запрос. */
    @Test
    fun anEmptyListMeansDifferentThingsBeforeAndAfterASearch() = runTest {
        val contents = viewModel()

        assertFalse(contents.state.value.isNarrowed)
        contents.search("аспирин")
        assertTrue(contents.state.value.isNarrowed)
        assertEquals(emptyList<String>(), contents.state.value.packs?.map { it.name })
    }

    /** Сброс возвращает область, а не всё подряд: человек сбрасывает запрос, а не место. */
    @Test
    fun resettingKeepsTheArea() = runTest {
        val contents = viewModel()
        contents.search("аспирин")
        contents.filter(PackageQuery.Filter.Expired)

        contents.reset()

        assertEquals(PackageQuery(medKitId = HOME_KIT), contents.state.value.query)
    }

    /**
     * Убрать аптечку вместе с лекарствами: пачек не остаётся, а экран знает, что уходить (PLAN
     * H3, ТЗ 4.1.1.2.3.1).
     */
    @Test
    fun removingWithTheDrugsEmptiesTheMedKitAndLeavesTheScreen() = runTest {
        val contents = viewModel()
        contents.askToRemove()

        var left = false
        contents.remove { left = true }

        assertTrue(left)
        assertTrue(medKits.medKits.none { it.id == HOME_KIT })
        assertEquals(listOf("Нурофен"), packages.packages.map { it.name })
    }

    /** С переносом лекарства целы и лежат в названной аптечке (ТЗ 4.1.1.2.3.2). */
    @Test
    fun removingWithATransferKeepsTheDrugs() = runTest {
        val contents = viewModel()
        contents.askToRemove()
        contents.pickTarget()
        contents.chooseTarget(SHARED_KIT)

        contents.remove(transferTo = SHARED_KIT) {}

        assertTrue(medKits.medKits.none { it.id == HOME_KIT })
        assertEquals(
            listOf(SHARED_KIT, SHARED_KIT),
            packages.packages.map { it.medKit.id }
        )
    }

    /** Отказ остаётся на экране причиной, а не исчезает молча. */
    @Test
    fun aRefusalIsShownAndNothingIsRemoved() = runTest {
        val contents = viewModel()
        contents.askToRemove()

        contents.remove(transferTo = Uuid.random()) {}

        assertEquals(
            MedKitContentsViewModel.Removing.Refused(MedKitRemoval.Outcome.TARGET_GONE),
            contents.state.value.removing
        )
        assertTrue(medKits.medKits.any { it.id == HOME_KIT })
    }
}
