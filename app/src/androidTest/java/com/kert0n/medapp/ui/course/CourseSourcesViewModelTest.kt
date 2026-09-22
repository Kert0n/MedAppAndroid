package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.SourceEstimates
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.CourseSourcesViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экран источников над настоящими сценариями и базой (PLAN H3 №16, U3): правка местная, а
 * «Сохранить» уносит её одним решением.
 *
 * Над Room, а не над подделками: состав идущего лечения укладывает `SourceEditing`, а он
 * перестраивает плановые пункты и брони, и подделка сказала бы о записи то, чего не было.
 */
@RunWith(AndroidJUnit4::class)
class CourseSourcesViewModelTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Третья коробка: на ней видно, что две перестановки подряд легли обе. */
    private val third: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000023")

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        val packages = database.packageRepository()
        packages.add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        packages.add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("12"), form = TABLET_FORM))
        packages.add(pack(id = third, name = "Кеторол", quantity = tablets("10"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        // Экран закрывают до базы: его чтения живут, пока жив он, и закрытая из-под них база
        // роняет соседнюю проверку, а не эту.
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    /** Что открыто: закрывается в обратном порядке — сначала экраны, потом база. */
    private val opened = mutableListOf<ViewModel>()

    private fun model(courseId: Uuid) = CourseSourcesViewModel(
        drafting = scenarios.courseDrafting,
        sources = scenarios.sourceEditing,
        estimates = SourceEstimates(),
        courses = database.courseRepository(),
        packages = database.packageRepository(),
        medKits = database.medKitRepository(),
        today = Today(clock, QuietClock),
        courseId = courseId
    ).also { opened += it }

    /** Черновик с назначением и двумя подключёнными коробками: первая — с тремя приёмами. */
    private suspend fun draft(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(3)),
                CourseDrafting.Edit.Attach(OTHER_PACK, Doses(0))
            )
        ) as CourseDrafting.Outcome.Saved
        return saved.draft.id
    }

    /** Тот же черновик с третьей коробкой: на нём видно порядок двух перестановок подряд. */
    private suspend fun draftOfThree(): Uuid {
        val id = draft()
        val stored = requireNotNull(database.courseRepository().findDraft(id))
        scenarios.courseDrafting.edit(id, stored.revision, listOf(CourseDrafting.Edit.Attach(third, Doses(0))))
        return id
    }

    /** Идущее лечение, которому нужно [total] приёмов: на нём видно, чем ограничен ползунок. */
    private suspend fun startedNeeding(total: Int): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(total)),
                CourseDrafting.Edit.Attach(PACK, Doses(3)),
                CourseDrafting.Edit.Attach(OTHER_PACK, Doses(0))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    /** Черновик с одной коробкой. */
    private suspend fun draftOfOneBox(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(3))
            )
        ) as CourseDrafting.Outcome.Saved
        return saved.draft.id
    }

    /** Идущее лечение с **одной** коробкой: на нём видно, что бывает, когда снимают последнюю. */
    private suspend fun startedWithOneBox(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(3))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    private suspend fun started(): Uuid {
        val id = draft()
        val draft = requireNotNull(database.courseRepository().findDraft(id))
        scenarios.courseActivation.activate(id, draft.revision)
        return id
    }

    /** Строка говорит, где коробка лежит, сколько в ней моего и сколько приёмов из неё выделено. */
    @Test
    fun aSourceRowTellsWhereTheBoxLiesAndWhatIsTakenFromIt() = runBlocking {
        val id = draft()
        val model = model(id)

        val state = watching(model.state) { it.awaiting(PATIENTLY) { state -> state.sources.size == 2 } }

        val first = state.sources.first()
        assertEquals("Нурофен", first.name)
        assertEquals("Домашняя", first.medKitName)
        assertEquals("20", first.availableToMe?.amount)
        assertEquals(3, first.allocatedDoses)
        // Три приёма по две таблетки — шесть: то же число привычной мерой.
        assertEquals("6", first.allocatedAmount?.amount)
    }

    /** Перестановка видна сразу, а в черновик уходит по «Сохранить» — одним решением. */
    @Test
    fun movingASourceShowsAtOnceAndIsWrittenOnSave() = runBlocking {
        val id = draft()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 2 }
            model.move(0, 1)
            // Порядок на экране уже новый, а в базе — ещё прежний: правка местная.
            val moved = state.awaiting(PATIENTLY) { it.sources.firstOrNull()?.packageId == OTHER_PACK }
            assertTrue(moved.hasUnsavedChanges)
            assertEquals(
                listOf(PACK, OTHER_PACK),
                requireNotNull(database.courseRepository().findDraft(id)).sources.map { it.pkg.id }
            )
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        val stored = requireNotNull(database.courseRepository().findDraft(id))
        assertEquals(listOf(OTHER_PACK, PACK), stored.sources.map { it.pkg.id })
    }

    /** Черновик ничего не занимал — отвязка у него не спрашивается (H3). */
    @Test
    fun aDraftDetachesWithoutAQuestion() = runBlocking {
        val id = draft()
        val model = model(id)

        val state = watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 2 }
            model.askToDetach(PACK)
            state.awaiting(PATIENTLY) { it.sources.size == 1 }
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        assertNull(state.asksToDetach)
        assertEquals(listOf(OTHER_PACK), requireNotNull(database.courseRepository().findDraft(id)).sources.map { it.pkg.id })
    }

    /** У идущего лечения отвязка освобождает коробку и снимает бронь — сначала вопрос (H3). */
    @Test
    fun aRunningCourseAsksBeforeItLetsABoxGo() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 2 && !it.isDraft }
            model.askToDetach(PACK)
            val asked = state.awaiting(PATIENTLY) { it.asksToDetach?.packageId == PACK }
            // Пока человек не ответил, состав цел: вопрос стоит до сценария.
            assertEquals(2, asked.sources.size)
            model.detach()
            state.awaiting(PATIENTLY) { it.sources.size == 1 }
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        assertEquals(listOf(OTHER_PACK), requireNotNull(database.courseRepository().findPlan(id)).sources.map { it.pkg.id })
    }

    /**
     * **Последнюю коробку отпускают одним решением.** Снять её и остаться без «Сохранить» нельзя:
     * кнопка живёт в списке, а список пуст — записать правку было бы нечем, и отвязать последний
     * препарат стало бы невозможно вовсе. Поэтому у последней коробки ответ на вопрос и есть
     * запись: человек подтвердил — состав записан пустым, лечение осталось без обеспечения.
     */
    @Test
    fun theLastBoxIsLetGoByTheAnswerItself() = runBlocking {
        val id = startedWithOneBox()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 1 && !it.isDraft }
            model.askToDetach(PACK)
            state.awaiting(PATIENTLY) { it.asksToDetach != null }
            model.detach()
            state.awaiting(PATIENTLY) { it.sources.isEmpty() && !it.hasUnsavedChanges && !it.isWriting }
        }

        assertEquals(emptyList<Uuid>(), requireNotNull(database.courseRepository().findPlan(id)).sources.map { it.pkg.id })
    }

    /**
     * **У черновика тоже спрашивают — о последней.** Обычную отвязку черновик не спрашивает: он
     * ничего не занимал. Но после последней коробки назначение остаётся ни с чем, и цена эта та
     * же, что у идущего лечения.
     */
    @Test
    fun evenADraftIsAskedAboutItsLastBox() = runBlocking {
        val id = draftOfOneBox()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 1 && it.isDraft }
            model.askToDetach(PACK)
            val asked = state.awaiting(PATIENTLY) { it.asksToDetach != null }
            assertEquals(true, asked.asksToDetach?.isLast)
            // Пока человек не ответил, состав цел.
            assertEquals(1, asked.sources.size)
            model.detach()
            state.awaiting(PATIENTLY) { it.sources.isEmpty() && !it.hasUnsavedChanges && !it.isWriting }
        }

        assertEquals(emptyList<Uuid>(), requireNotNull(database.courseRepository().findDraft(id)).sources.map { it.pkg.id })
    }

    /**
     * Несколько движений подряд — **одна** запись: человек собирает стек, каким хочет, и лишь
     * потом решает. Порядок из трёх коробок: у двух любые две перестановки вернули бы стек к
     * исходному, и проверка не отличила бы сделанное от несделанного.
     *
     * Редакция при этом растёт на число переходов, а не на число решений: каждый переход домена
     * поднимает её сам, и считать решения по ней нельзя — их видно по тому, что до «Сохранить» в
     * базе не менялось ничего.
     */
    @Test
    fun manyMovesGoIntoTheStoreAsOneDecision() = runBlocking {
        val id = draftOfThree()
        val before = requireNotNull(database.courseRepository().findDraft(id)).revision
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 3 }
            // [Нурофен, Ибупрофен, Кеторол] → [Ибупрофен, Кеторол, Нурофен] → [Кеторол, Ибупрофен, Нурофен]
            model.move(0, 2)
            model.move(0, 1)
            state.awaiting(PATIENTLY) { it.sources.map { source -> source.packageId } == listOf(third, OTHER_PACK, PACK) }
            // До «Сохранить» в базе не изменилось ничего: два движения — ещё не решение.
            assertEquals(before, requireNotNull(database.courseRepository().findDraft(id)).revision)
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        val stored = requireNotNull(database.courseRepository().findDraft(id))
        assertEquals(listOf(third, OTHER_PACK, PACK), stored.sources.map { it.pkg.id })
    }

    /**
     * Ползунок второй коробки останавливается на своём пределе и **не двигает первую**:
     * перераспределяет человек, а не автоматика (PLAN C1 «Ползунок»). Нужно пять приёмов, первой
     * выделено три — второй остаётся два, сколько бы человек ни тянул.
     */
    @Test
    fun theSecondSliderStopsAtItsLimitAndLeavesTheFirstAlone() = runBlocking {
        val id = startedNeeding(5)
        val model = model(id)

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.sources.getOrNull(1)?.maxDoses != null }
            assertEquals(2, shown.sources[1].maxDoses)
            model.allocate(OTHER_PACK, 99)
            state.awaiting(PATIENTLY) { it.sources.getOrNull(1)?.allocatedDoses == 2 }
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        val plan = requireNotNull(database.courseRepository().findPlan(id))
        assertEquals(Doses(2), plan.sources.first { it.pkg.id == OTHER_PACK }.allocatedDoses)
        assertEquals(Doses(3), plan.sources.first { it.pkg.id == PACK }.allocatedDoses)
    }

    /**
     * Освободил первую коробку — предел второй вырос **сразу**, до всякой записи: предел считает
     * экран, и палец не ждёт базу (решение владельца 2026-09-16).
     */
    @Test
    fun freeingTheFirstBoxRaisesTheLimitOfTheSecondAtOnce(): Unit = runBlocking {
        val id = startedNeeding(5)
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.getOrNull(1)?.maxDoses == 2 }
            model.allocate(PACK, 0)
            val raised = state.awaiting(PATIENTLY) { it.sources.getOrNull(1)?.maxDoses == 5 }
            // В базе ещё прежний состав: предел вырос от местной правки, а не от записи.
            assertTrue(raised.hasUnsavedChanges)
            assertEquals(
                Doses(3),
                requireNotNull(database.courseRepository().findPlan(id)).sources.first { it.pkg.id == PACK }.allocatedDoses
            )
        }
    }

    /**
     * У черновика обеспечения нет, и потолок — сколько даёт коробка: двадцать таблеток по две —
     * десять приёмов, двенадцать — шесть (PLAN H3 №16).
     */
    @Test
    fun aDraftSliderIsCappedByWhatTheBoxGives() = runBlocking {
        val id = draft()
        val model = model(id)

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.sources.size == 2 && it.sources[0].maxDoses != null }
            assertEquals(10, shown.sources[0].maxDoses)
            assertEquals(6, shown.sources[1].maxDoses)
            model.allocate(OTHER_PACK, 99)
            state.awaiting(PATIENTLY) { it.sources.getOrNull(1)?.allocatedDoses == 6 }
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
        }

        val stored = requireNotNull(database.courseRepository().findDraft(id))
        assertEquals(Doses(6), stored.sources.first { it.pkg.id == OTHER_PACK }.allocatedDoses)
    }

    /**
     * Перестановка источников обеспечения не меняет, пока выделения прежние (PLAN U3): порядок —
     * это очередь расходования, а не количество лекарства.
     */
    @Test
    fun reorderingLeavesCoverageAloneWhenAllocationsStay() = runBlocking {
        val id = startedNeeding(5)
        val model = model(id)

        watching(model.state) { state ->
            val before = state.awaiting(PATIENTLY) { it.coverage != null }.coverage
            model.move(0, 1)
            model.save()
            state.awaiting(PATIENTLY) { !it.hasUnsavedChanges && !it.isWriting }
            val after = state.awaiting(PATIENTLY) { it.sources.firstOrNull()?.packageId == OTHER_PACK }
            assertEquals(before, after.coverage)
        }
    }

    private companion object {
        /**
         * Сколько ждать нового состояния. Дольше умолчания: между действием и состоянием здесь
         * стоят настоящий сценарий и потоки Room, а в полном прогоне эмулятор занят и соседними
         * проверками — пяти секунд ему тогда не хватает.
         */
        val PATIENTLY: Duration = 15.seconds
    }
}
