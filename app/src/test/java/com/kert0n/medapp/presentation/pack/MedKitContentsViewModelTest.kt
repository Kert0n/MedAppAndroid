package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.feature.packages.PackageQuery
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.CAPSULE_FORM
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
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Содержимое полки и все лекарства (PLAN H3 №4, №5). Здесь — **каким запросом** экран
 * спрашивает базу и что он делает с ответом; сам подбор и порядок принадлежат запросу базы и
 * проверяются на ней (`PackageQueryDaoTest`). Как это нарисовано — `MedKitContentsScreenTest`.
 */
class MedKitContentsViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    /**
     * Хранение, которое вдобавок запоминает, о чём его спросили: запрос и есть предмет проверки.
     * Запросы — поток: экран собирает состояние вне главного потока, и запрос к базе приходит
     * не раньше состояния, а рядом с ним, — проверка ждёт запроса, а не читает его наугад.
     */
    private class Asked(private val real: FakePackages) : PackageRecords by real {

        val queries = MutableStateFlow<List<PackageQuery>>(emptyList())

        override fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>> {
            queries.value += query
            return real.list(query, today)
        }
    }

    private val stored = FakePackages()

    private val packages = Asked(stored)

    private val courses = FakeCourses()

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача")
    )

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private fun removal(transactions: Transactions = DirectTransactions) = MedKitRemoval(
        medKits = medKits,
        packages = packages,
        removal = PackageRemoval(packages, queue, DirectTransactions, clock),
        relocation = PackageRelocation(packages, medKits, FakeCourses(), queue, DirectTransactions, clock),
        queue = queue,
        transactions = transactions,
        clock = clock
    )

    private fun viewModel(
        medKitId: Uuid? = HOME_KIT,
        removal: MedKitRemoval = removal()
    ) = MedKitContentsViewModel(
        removal = removal,
        packages = packages,
        medKits = medKits,
        courses = courses,
        today = Today(clock, QuietClock),
        medKitId = medKitId
    )

    /** Запрос, с которым экран пришёл в базу последним, — когда он до неё дошёл. */
    private fun lastQuery(expected: (PackageQuery) -> Boolean = { true }): PackageQuery = runBlocking {
        packages.queries.awaiting { it.isNotEmpty() && expected(it.last()) }.last()
    }

    /**
     * **Три независимых поля, а не история нажатий.** Искал, потом сузил — и сузил, потом искал
     * — это один и тот же список: конвейер один (PLAN H4).
     *
     * Красная проверка: складывать нажатия в стопку и применять их по порядку — два одинаковых
     * запроса человека дают разные списки, и объяснить ему это нечем.
     */
    @Test
    fun theOrderOfTapsDoesNotChangeTheQuery() {
        val first = viewModel()
        watching(first.state) { state ->
            state.awaiting { it.isLoaded }
            first.search("нуро")
            first.narrow(Narrowing.Expired)
            state.awaiting { it.narrowing == Narrowing.Expired && it.text == "нуро" }
        }
        val searchedThenNarrowed = lastQuery { it.text == "нуро" && it.filter == PackageQuery.Filter.Expired }

        val second = viewModel()
        watching(second.state) { state ->
            state.awaiting { it.isLoaded }
            second.narrow(Narrowing.Expired)
            second.search("нуро")
            state.awaiting { it.narrowing == Narrowing.Expired && it.text == "нуро" }
        }

        assertEquals(searchedThenNarrowed, lastQuery { it.text == "нуро" && it.filter == PackageQuery.Filter.Expired })
    }

    /**
     * **Сужение ровно одно.** Второе не ложится поверх первого: два сразу сузили бы список до
     * пустого чаще, чем помогли. Правило держит тип — сужение здесь поле, а не список, — и
     * снять его, не переписав тип, нечем; проверка сторожит перевод этого поля в запрос базы.
     */
    @Test
    fun onlyOneNarrowingAtATime() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.narrow(Narrowing.Expired)
            model.narrow(Narrowing.OnCourse)
            state.awaiting { it.narrowing == Narrowing.OnCourse }
        }

        assertEquals(Narrowing.OnCourse, state.narrowing)
        assertEquals(PackageQuery.Filter.OnCourse, lastQuery { it.filter == PackageQuery.Filter.OnCourse }.filter)
    }

    /**
     * **Сброс оставляет область.** Человек сбрасывает запрос, а не место: иначе «Сбросить» на
     * полке показало бы ему все аптечки сразу.
     *
     * Красная проверка: сбрасывать в `PackageQuery()` — полка теряется, и вернуться в неё
     * можно только выйдя с экрана.
     */
    @Test
    fun resettingKeepsTheArea() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.search("нуро")
            model.narrow(Narrowing.Expired)
            model.order(Ordering.EXPIRY)
            state.awaiting { it.ordering == Ordering.EXPIRY }
            model.reset()
            state.awaiting { it.text.isEmpty() && it.narrowing == null && it.ordering == Ordering.NAME }
        }

        assertEquals(PackageQuery(medKitId = HOME_KIT), lastQuery { it.text.isEmpty() && it.filter == null })
    }

    /**
     * **Чем сузить, предлагается по всей области, а не по тому, что уже нашлось.** Иначе
     * выбранная категория исчезает из списка, и вернуться к другой нечем.
     *
     * Красная проверка: собирать категории из показанного — после выбора «Обезболивающие» в
     * списке остаётся одна строка, та же самая, и выбор превращается в тупик.
     */
    @Test
    fun whatToNarrowByComesFromTheWholeArea() {
        stored.lying(
            pack(id = PACK, name = "Нурофен", category = "Обезболивающие", form = CAPSULE_FORM),
            pack(id = OTHER_PACK, name = "Парацетамол", category = "Жаропонижающие", form = TABLET_FORM)
        )
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.categories.size == 2 }
            model.search("Нурофен")
            model.narrow(Narrowing.OfCategory("Обезболивающие"))
            state.awaiting { it.narrowing != null && it.packages.size == 1 }
        }

        assertEquals(listOf("Жаропонижающие", "Обезболивающие"), state.categories)
        assertEquals(
            listOf(CAPSULE_FORM.toPresentationDTO(), TABLET_FORM.toPresentationDTO()),
            state.forms.sortedBy { it.name }
        )
    }

    /**
     * Убранная полка закрывает экран: смотреть в ней больше нечего. Пустую убирают сразу — ни
     * переносить, ни выбрасывать нечего.
     */
    @Test
    fun anEmptyShelfIsRemovedAtOnce() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.askToRemove()
            model.remove()
            state.awaiting { it.isRemoved }
        }

        assertTrue(state.isRemoved)
        assertEquals(listOf(SHARED_KIT), medKits.medKits.map { it.id })
    }

    /**
     * **Второе решение поверх первого не начинается.** Нажатие, пока сценарий ещё отвечает, не
     * зовёт его второй раз: полку убирают один раз, и второй заход разбирал бы то, чего уже нет.
     *
     * Красная проверка: снять признак работы — уборка зовётся дважды, и второй раз отвечает
     * `MED_KIT_GONE`, то есть человек получает исход не о своём решении.
     */
    @Test
    fun aSecondDecisionDoesNotStartOnTopOfTheFirst() {
        stored.lying(pack(id = PACK))
        // Уборка держится на пороге: в подделках она мгновенна, и второе нажатие пришло бы,
        // когда первое уже кончилось, — тогда замка не видно вовсе.
        val held = HeldTransactions()
        val model = viewModel(removal = removal(held))

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.askToRemove()
            model.remove()
            model.remove()
            held.door.release()
            state.awaiting { it.isRemoved }
        }

        assertEquals(1, held.door.waiting)
        assertTrue(state.isRemoved)
        assertEquals(emptyList<Any>(), stored.packages)
        assertNull(state.removalRefusal)
    }

    /**
     * Отказ виден причиной, и ничего не убрано: полка, ждущая ответа сервера на другое решение,
     * молча не исчезает.
     *
     * Красная проверка: считать любой исход согласием — экран закрывается, полка остаётся, и
     * человек уверен, что убрал её.
     */
    @Test
    fun aRefusalIsToldAndNothingIsRemoved() {
        runBlocking { medKits.mark(HOME_KIT, MedKitStatus.REMOVING) }
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.askToRemove()
            model.remove()
            state.awaiting { it.removalRefusal != null }
        }

        assertEquals(RemovalRefusal.BUSY, state.removalRefusal)
        assertEquals(RemovalStep.ASKING, state.removing)
        assertTrue(medKits.medKits.any { it.id == HOME_KIT })
    }

    /**
     * Отказала цель — выбор цели остаётся открытым: другую полку выбирают там, где выбирали эту,
     * а не заново из первого вопроса.
     *
     * Красная проверка: любой отказ возвращать к первому вопросу — список полок закрывается, и
     * человек открывает его снова ради того же выбора.
     */
    @Test
    fun aTargetRefusalKeepsThePickerOpen() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.askToRemove()
            model.pickTarget()
            medKits.forget(SHARED_KIT)
            model.remove(SHARED_KIT)
            state.awaiting { it.removalRefusal != null }
        }

        assertEquals(RemovalRefusal.TARGET_GONE, state.removalRefusal)
        assertEquals(RemovalStep.PICKING_TARGET, state.removing)
        assertTrue(medKits.medKits.any { it.id == HOME_KIT })
    }

    /**
     * Из местной аптечки не выходят — её убирают: остальных у неё нет, и оставлять полку некому
     * (PLAN E6). Отказ назван, а не проглочен.
     */
    @Test
    fun onlyASharedShelfCanBeLeft() {
        val model = viewModel(medKitId = SHARED_KIT)

        watching(model.state) { state ->
            state.awaiting { it.isLoaded }
            model.askToRemove()
            model.leave()
            state.awaiting { it.isRemoved || it.removalRefusal != null }
        }

        assertEquals(RemovalRefusal.NOT_SHARED, model.state.value.removalRefusal)
    }

    /**
     * Полку убрали у всех, пока экран открыт, — экран говорит, что её больше нет, а не зовёт завести
     * в неё коробку.
     */
    @Test
    fun aShelfThatVanishedIsSaidToBeGone() {
        val model = viewModel(medKitId = SHARED_KIT)

        val state = watching(model.state) { state ->
            state.awaiting { it.isLoaded && it.medKit != null }
            medKits.forget(SHARED_KIT)
            state.awaiting { it.isShelfGone }
        }

        assertFalse(state.isRemoved)
    }
}
