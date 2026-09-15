package com.kert0n.medapp.ui.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экран источников над настоящими сценариями и базой (PLAN H3 №16, U3): что человек сделал со
 * стеком, то и записано — кнопки «Сохранить» у экрана нет.
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
    fun tearDown() = database.close()

    private fun model(courseId: Uuid) = CourseSourcesViewModel(
        drafting = scenarios.courseDrafting,
        sources = scenarios.sourceEditing,
        courses = database.courseRepository(),
        packages = database.packageRepository(),
        medKits = database.medKitRepository(),
        today = Today(clock, QuietClock),
        courseId = courseId
    )

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

    /** Отпущенная строка записана: перестановка уходит в черновик без всякой кнопки. */
    @Test
    fun movingASourceIsWrittenWithoutASaveButton() = runBlocking {
        val id = draft()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 2 }
            model.move(0, 1)
            state.awaiting(PATIENTLY) { it.sources.firstOrNull()?.packageId == OTHER_PACK }
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
            val asked = state.awaiting(PATIENTLY) { it.asksToDetach == PACK }
            // Пока человек не ответил, состав цел: вопрос стоит до сценария.
            assertEquals(2, asked.sources.size)
            model.detach()
            state.awaiting(PATIENTLY) { it.sources.size == 1 }
        }

        assertEquals(listOf(OTHER_PACK), requireNotNull(database.courseRepository().findPlan(id)).sources.map { it.pkg.id })
    }

    /**
     * Две правки подряд ложатся обе и в своём порядке: вторая ждёт первую, иначе ушла бы с
     * редакцией, которую первая уже сдвинула.
     *
     * Порядок из трёх коробок, а не из двух: у двух любые две перестановки возвращают стек к
     * исходному, и проверка не отличила бы «обе легли» от «не легла ни одна».
     */
    @Test
    fun twoMovesInARowBothLandInTheOrderTheyWereMade() = runBlocking {
        val id = draftOfThree()
        val before = requireNotNull(database.courseRepository().findDraft(id)).revision
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.sources.size == 3 }
            // [Нурофен, Ибупрофен, Кеторол] → [Ибупрофен, Кеторол, Нурофен] → [Кеторол, Ибупрофен, Нурофен]
            model.move(0, 2)
            model.move(0, 1)
            state.awaiting(PATIENTLY) { it.sources.map { source -> source.packageId } == listOf(third, OTHER_PACK, PACK) }
        }

        val stored = requireNotNull(database.courseRepository().findDraft(id))
        assertEquals(listOf(third, OTHER_PACK, PACK), stored.sources.map { it.pkg.id })
        // Обе записи дошли: редакция выросла дважды, а не один раз.
        assertEquals(before.number + 2, stored.revision.number)
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
