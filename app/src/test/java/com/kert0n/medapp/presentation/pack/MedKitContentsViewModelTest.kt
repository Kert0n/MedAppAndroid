package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Содержимое аптечки и все лекарства (PLAN H3 №4, №5): конвейер один, и порядок нажатий его не
 * меняет (PLAN H4).
 */
class MedKitContentsViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages().lying(
        pack(id = PACK, name = "Нурофен"),
        pack(id = OTHER_PACK, name = "Парацетамол")
    )

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача")
    )

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun viewModel(
        medKitId: Uuid? = HOME_KIT,
        transactions: Transactions = DirectTransactions
    ) = MedKitContentsViewModel(
        removal = MedKitRemoval(
            medKits = medKits,
            packages = packages,
            removal = PackageRemoval(packages, queue, DirectTransactions, clock),
            relocation = PackageRelocation(packages, medKits, FakeCourses(), queue, DirectTransactions, clock),
            queue = queue,
            transactions = transactions,
            clock = clock
        ),
        packages = packages,
        medKits = medKits,
        today = Today(clock),
        savedState = SavedStateHandle(
            medKitId?.let { mapOf(RouteArguments.MED_KIT_ID to it.toString()) } ?: emptyMap()
        )
    )

    private suspend fun StateFlow<MedKitContentsUiState>.loaded() = awaiting { it.isLoaded }

    /**
     * Порядок нажатий итог не меняет: состояние держит три независимых поля, а не историю
     * (PLAN H4).
     *
     * Красная проверка: складывать нажатия в историю — «искал, потом фильтровал» даст не то же,
     * что наоборот.
     */
    @Test
    fun theOrderOfPressesDoesNotChangeTheResult() {
        val first = viewModel()
        val second = viewModel()

        val one = watching(first.state) { state ->
            state.loaded()
            first.search("нур")
            first.narrow(Narrowing.Expired)
            first.order(Ordering.QUANTITY)
            state.awaiting { it.ordering == Ordering.QUANTITY }.let { it.text to (it.narrowing to it.ordering) }
        }
        val other = watching(second.state) { state ->
            state.loaded()
            second.order(Ordering.QUANTITY)
            second.narrow(Narrowing.Expired)
            second.search("нур")
            state.awaiting { it.text == "нур" }.let { it.text to (it.narrowing to it.ordering) }
        }

        assertEquals(one, other)
    }

    /** Поиск в аптечке остаётся в ней: область — поле запроса, а не отдельный экран. */
    @Test
    fun searchingInsideAShelfStaysInsideIt() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.loaded()
            model.search("нур")
            state.awaiting { it.text == "нур" }
        }

        assertTrue(!state.isEverywhere)
    }

    /** Без названной аптечки ищется везде, и каждая строка называет свою аптечку. */
    @Test
    fun withoutAShelfTheSearchLooksEverywhere() {
        val state = watching(viewModel(medKitId = null).state) { it.loaded() }

        assertTrue(state.isEverywhere)
        assertTrue(state.isEverywhere)
        assertEquals("Домашняя", state.placeNames[HOME_KIT])
    }

    /** Внутри одной аптечки строка её не повторяет: человек и так знает, куда пришёл. */
    @Test
    fun insideOneShelfTheRowsDoNotRepeatItsName() {
        val state = watching(viewModel().state) { it.loaded() }

        assertEquals(emptyMap<Uuid, String>(), state.placeNames)
    }

    /**
     * Сброс возвращает список и **оставляет область**: человек сбрасывает запрос, а не место.
     *
     * Красная проверка: сбрасывать запрос целиком — человек оказывается во всех лекарствах.
     */
    @Test
    fun resettingKeepsTheArea() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.loaded()
            model.search("нур")
            model.narrow(Narrowing.Expired)
            state.awaiting { it.isNarrowed }
            model.reset()
            state.awaiting { !it.isNarrowed }
        }

        assertTrue(!state.isEverywhere)
        assertEquals("", state.text)
        assertEquals(null, state.narrowing)
    }

    /** Нажатие на выбранный фильтр снимает его: иначе выйти из него было бы нечем. */
    @Test
    fun theChosenFilterCanBeUnchosen() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.loaded()
            model.narrow(Narrowing.Expired)
            state.awaiting { it.narrowing != null }
            model.narrow(null)
            state.awaiting { it.narrowing == null }
        }

        assertEquals(null, state.narrowing)
    }

    /**
     * «Здесь ничего нет» и «ничего не нашлось» — разные случаи, и различает их сужение.
     *
     * Красная проверка: одно сообщение на оба — человеку предложат завести упаковку там, где ему
     * нужно сбросить запрос.
     */
    @Test
    fun anEmptyShelfAndAnEmptySearchAreDifferentThings() {
        val model = viewModel()

        val narrowed = watching(model.state) { state ->
            state.loaded()
            model.search("такого нет")
            state.awaiting { it.isNarrowed }
        }

        assertTrue(narrowed.isNarrowed)
        assertTrue(!watching(viewModel().state) { it.loaded() }.isNarrowed)
    }

    /** Убранная полка уводит с экрана, а лекарства переезжают. */
    @Test
    fun removingAShelfWithATransferKeepsTheMedicines() {
        val model = viewModel()

        watching(model.state) { state ->
            state.loaded()
            model.askToRemove()
            model.pickTarget()
            model.remove(transferTo = SHARED_KIT)
            state.awaiting { it.isRemoved }
        }

        assertEquals(emptyList<Uuid>(), medKits.medKits.map { it.id }.filter { it == HOME_KIT })
        assertEquals(listOf(SHARED_KIT, SHARED_KIT), packages.packages.map { it.medKit.id })
    }

    /** Уборка вместе с лекарствами уносит и их. */
    @Test
    fun removingAShelfWithItsMedicinesTakesThemToo() {
        val model = viewModel()

        watching(model.state) { state ->
            state.loaded()
            model.askToRemove()
            model.remove()
            state.awaiting { it.isRemoved }
        }

        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Без разговора ничего не убирается. */
    @Test
    fun withoutTheQuestionNothingIsRemoved() {
        val model = viewModel()

        watching(model.state) { state ->
            state.loaded()
            model.remove()
            state.awaiting { !it.isRemoved }
        }

        assertEquals(2, medKits.medKits.size)
    }

    /** Чем сузить, предлагается по всей области, а не по тому, что уже нашлось. */
    @Test
    fun whatToNarrowByComesFromTheWholeArea() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.loaded()
            model.search("такого нет")
            state.awaiting { it.isNarrowed }
        }

        assertTrue(state.packages.isEmpty())
        assertEquals(emptyList<String>(), state.categories)
    }

    /**
     * Порядок, в котором список пришёл из чтения, состояние не переставляет: просроченные идут
     * первыми при любой сортировке, и решает это запрос (PLAN H4), а не экран.
     *
     * Красная проверка: отсортировать список в состоянии — этот порядок перестанет совпадать с
     * прочитанным.
     */
    @Test
    fun theReadingOrderIsKept() {
        val model = viewModel()

        val state = watching(model.state) { it.loaded() }

        assertEquals(
            packages.packages.map { it.id },
            state.packages.map { it.id }
        )
    }

    /**
     * Пока уборка в пути, второй не начинается: разговор больше не открывается, и второе решение
     * не уходит сценарию поверх первого.
     *
     * Красная проверка: снять замок — второе нажатие «Убрать» начинает вторую уборку.
     */
    @Test
    fun aSecondRemovalIsNotStartedWhileTheFirstIsOnItsWay() {
        val transactions = HeldTransactions()
        val model = viewModel(transactions = transactions)

        watching(model.state) { state ->
            state.loaded()
            model.askToRemove()
            model.remove()
            model.askToRemove()
            model.remove()
            transactions.door.release()
            state.awaiting { it.isRemoved }
        }

        assertEquals(1, transactions.door.waiting)
    }
}
