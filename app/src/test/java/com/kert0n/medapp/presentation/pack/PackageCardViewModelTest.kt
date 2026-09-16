package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeSyncOperations
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldPackages
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Карточка упаковки (PLAN H3 №6): что она читает, когда говорит «загрузка», «есть» и «нет», и
 * как отвечает на «выбросить». Как это нарисовано — `PackageCardScreenTest`.
 */
class PackageCardViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    /** Полки, которые помнят, спрашивали ли их все разом: ради одного имени этого не делают. */
    private class Counted(private val real: FakeMedKits) : MedKitStorageRepository by real {

        var askedForAll = 0

        override fun observeAll(today: LocalDate): Flow<List<MedKitProjection>> {
            askedForAll++
            return real.observeAll(today)
        }
    }

    private val stored = FakePackages()

    private val medKits = Counted(
        FakeMedKits(
            medKit(id = HOME_KIT, name = "Домашняя"),
            medKit(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participantCount = 2)
        )
    )

    private val courses = FakeCourses()

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private val operations = FakeSyncOperations()

    private fun viewModel(
        packages: PackageStorageRepository = stored,
        transactions: Transactions = DirectTransactions
    ) = PackageCardViewModel(
        removal = PackageRemoval(packages, queue, transactions, clock),
        packages = packages,
        medKits = medKits,
        courses = courses,
        operations = operations,
        today = Today(clock, QuietClock),
        packageId = PACK
    )

    /**
     * До первого чтения карточка не говорит ни «есть», ни «нет»: сказать «этой упаковки больше
     * нет», пока база отвечает, значило бы опровергнуть себя через миг.
     *
     * Красная проверка: считать «нет» любое отсутствие коробки в состоянии — экран скажет это до
     * чтения.
     */
    @Test
    fun beforeTheFirstReadingTheCardWaits() {
        stored.lying(pack(id = PACK, name = "Нурофен"))
        val held = HeldPackages(stored)
        val model = viewModel(packages = held)

        watching(model.state) { state ->
            assertTrue(state.value.isLoading)
            assertFalse(state.value.isGone)
            held.door.release()
            state.awaiting { it.pack != null }
        }
    }

    /** Коробки не стало, пока карточка открыта: человек читает об этом, а экран не уходит сам. */
    @Test
    fun aBoxThatVanishedWhileOpenIsSaidToBeGone() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            stored.forget(PACK)
            state.awaiting { it.isGone }
        }

        assertTrue(state.isGone)
        assertFalse(state.isRemoved)
        assertFalse(state.isLoading)
    }

    /**
     * Ради одного имени карточка не читает все полки: спрашивается та, на которой коробка лежит.
     *
     * Красная проверка: взять `observeAll` и выбрать имя по тождеству — счётчик перестаёт быть
     * нулём.
     */
    @Test
    fun theCardDoesNotAskForOtherShelves() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        val state = watching(model.state) { state -> state.awaiting { it.medKitName != null } }

        assertEquals("Домашняя", state.medKitName)
        assertEquals(0, medKits.askedForAll)
    }

    /** Держащее лечение названо именем, а не тождеством; последний приём — днём человека. */
    @Test
    fun theHoldingCourseAndTheLastUseAreNamed() {
        stored.lying(pack(id = PACK))
        stored.heldBy[PACK] = COURSE
        stored.lastUsed[PACK] = Instant.parse("2026-09-12T22:30:00Z")
        courses.records[COURSE] = courseRecord(id = COURSE, title = "Нурофен, 7 дней").projection()
        val model = viewModel()

        val state = watching(model.state) { state -> state.awaiting { it.holdingCourseTitle != null } }

        assertEquals("Нурофен, 7 дней", state.holdingCourseTitle)
        // В Москве это уже 13-е: день берётся в зоне человека, а не по Гринвичу.
        assertEquals(LocalDate.parse("2026-09-13"), state.lastUsedOn)
    }

    /** Без лечения карточка о нём молчит. */
    @Test
    fun withoutACourseNothingIsNamed() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        val state = watching(model.state) { state -> state.awaiting { it.pack != null } }

        assertNull(state.holdingCourseTitle)
        assertNull(state.lastUsedOn)
    }

    /** Выброшенной с своей полки коробки нет сразу, и карточка уходит. */
    @Test
    fun aThrownAwayBoxLeavesTheCard() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            state.awaiting { it.asksToRemove }
            model.remove()
            state.awaiting { it.isRemoved }
        }

        assertTrue(state.isRemoved)
        assertEquals(emptyList<Any>(), stored.packages)
    }

    /** Без подтверждения сценарий не зовётся: «выбросить» — опасное действие (PLAN H3). */
    @Test
    fun withoutConfirmationNothingIsThrownAway() {
        stored.lying(pack(id = PACK))
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.remove()
            model.askToRemove()
            model.dismissRemoval()
            model.remove()
            state.awaiting { !it.asksToRemove }
        }

        assertEquals(listOf(PACK), stored.packages.map { it.id })
        assertFalse(model.state.value.isRemoved)
    }

    /**
     * Второе нажатие не начинает второго дела: коробку выбрасывают один раз, и ответ на второе
     * не подменил бы ответ на первое.
     *
     * Красная проверка: снять признак работы — сценарий зовётся дважды, второй раз отвечает
     * `GONE`, и в дверь приходят двое.
     */
    @Test
    fun aSecondTapCallsTheScenarioOnce() {
        stored.lying(pack(id = PACK))
        val held = HeldTransactions()
        val model = viewModel(transactions = held)

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.remove()
            model.remove()
            held.door.release()
            state.awaiting { it.isRemoved }
        }

        assertEquals(1, held.door.waiting)
        assertTrue(state.isRemoved)
    }

    /**
     * На общей полке решение принято, а коробка ждёт ответа: карточка остаётся, и о пометке
     * говорит сама коробка — статусом, который переживёт закрытие экрана.
     *
     * Красная проверка: считать любой исход уходом — экран закроется, а коробка останется на
     * полке, и человек уверен, что её нет.
     */
    @Test
    fun onASharedShelfTheBoxStaysAndIsMarked() {
        stored.lying(pack(id = PACK, medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref))
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.remove()
            state.awaiting { it.pack?.status == PackageStatus.REMOVING }
        }

        assertFalse(state.isRemoved)
        assertFalse(state.asksToRemove)
        assertFalse(state.isBusy)
        assertEquals(listOf(PACK), stored.packages.map { it.id })
    }

    /** Коробка, ждущая другого решения, отвечает отказом, и ничего не меняется. */
    @Test
    fun aBusyBoxRefusesAndNothingChanges() {
        stored.lying(pack(id = PACK))
        runBlocking { stored.mark(PACK, PackageStatus.REMOVING, by = Uuid.random()) }
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.pack != null }
            model.askToRemove()
            model.remove()
            state.awaiting { it.isBusy }
        }

        assertFalse(state.isRemoved)
        assertFalse(state.asksToRemove)
        assertEquals(listOf(PACK), stored.packages.map { it.id })
    }
}
