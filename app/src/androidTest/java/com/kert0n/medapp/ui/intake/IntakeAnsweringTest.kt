package com.kert0n.medapp.ui.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.intake.IntakeCardViewModel
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPlanViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ответ на приём над настоящими сценариями и базой (PLAN H3 №12, №18): быстрый — из строки дня,
 * подробный — с карточки пункта. Записывает оба один и тот же сценарий, и проверяется здесь то,
 * что добавляют к нему экраны: чем они его зовут и что делают с его исходами.
 */
@RunWith(AndroidJUnit4::class)
class IntakeAnsweringTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios

    /** Полдень по Гринвичу: пункт девяти утра по Москве уже наступил, а день ещё не кончился. */
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun dayModel() = DayPlanViewModel(
        today = Today(clock, QuietClock),
        planning = DayPlanning(Today(clock, QuietClock), database.reportRepository()),
        confirmation = scenarios.intakeConfirmation,
        clock = clock
    ).also { opened += it }

    private fun cardModel(courseId: Uuid, intakeId: Uuid) = IntakeCardViewModel(
        confirmation = scenarios.intakeConfirmation,
        vocabulary = VocabularyRoomRepository(database.vocabulary()),
        today = Today(clock, QuietClock),
        clock = clock,
        courses = database.courseRepository(),
        intakes = database.intakeRepository(),
        courseId = courseId,
        intakeId = intakeId
    ).also { opened += it }

    /** Лечение на четыре приёма по две таблетки из коробки на двадцать; первый пункт — сегодня. */
    private suspend fun started(expiresOn: LocalDate? = null): Uuid {
        database.packageRepository().add(
            pack(
                id = PACK,
                name = "Нурофен",
                quantity = tablets("20"),
                form = TABLET_FORM,
                expiresOn = expiresOn?.let { ExpiryDate(it) }
            )
        )
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    /** Первый пункт лечения — по времени, а не по номеру: сегодняшний, тот, на который отвечают. */
    private suspend fun firstIntake(courseId: Uuid) = database.intakeRepository().ofCourse(courseId)
        .filterIsInstance<CourseIntake>()
        .minBy { it.slot.at }

    /**
     * Быстрый ответ берёт **записанное в пункте** — плановую пачку и плановую дозу — и момент
     * «сейчас»: человек нажал одну кнопку и ничего не называл. Собирай экран дозу сам — и
     * записанное разошлось бы с назначенным, о котором человек и отвечал.
     */
    @Test
    fun theFastAnswerWritesWhatThePlanSaid() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        val model = dayModel()

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { it.ready()?.items?.isNotEmpty() == true }
            model.confirm(intakeId)
            page.awaiting(PATIENTLY) { state ->
                state.ready()?.items.orEmpty().none { it.canAnswer }
            }
        }

        val intake = database.intakeRepository().find(intakeId)
        assertEquals(IntakeStatus.TAKEN, intake?.status)
        // Две таблетки ушли из коробки: приём — расход, а не отметка (PLAN D6).
        assertEquals(tablets("18"), database.packageRepository().find(PACK)?.quantity)
    }

    /**
     * Второе нажатие, пока идёт первое, ничего не начинает. Без этого сторожа нетерпеливый палец
     * списывает коробку дважды, а пункт принимают один раз — и второй расход уже ничей.
     */
    @Test
    fun pressingTakenTwiceSpendsTheBoxOnce() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        val model = dayModel()

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { it.ready()?.items?.isNotEmpty() == true }
            model.confirm(intakeId)
            model.confirm(intakeId)
            page.awaiting(PATIENTLY) { state -> state.ready()?.items.orEmpty().none { it.canAnswer } }
        }

        assertEquals(tablets("18"), database.packageRepository().find(PACK)?.quantity)
    }

    /**
     * Вопрос быстрый путь не проглатывает: просроченная коробка ничего не записывает, а ведёт на
     * карточку пункта — отвечать на вопрос человек должен зная (PLAN D6).
     */
    @Test
    fun aQuestionWritesNothingAndSendsThePersonToTheCard() = runBlocking {
        val courseId = started(expiresOn = LocalDate.of(2027, 3, 1))
        val intakeId = firstIntake(courseId).id
        val model = dayModel()

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { it.ready()?.items?.isNotEmpty() == true }
            model.confirm(intakeId)
            watching(model.asksAbout) { asked -> asked.awaiting(PATIENTLY) { it != null } }
        }

        assertNotNull(model.asksAbout.value)
        assertEquals(intakeId, model.asksAbout.value?.intakeId)
        assertEquals(IntakeStatus.PLANNED, database.intakeRepository().find(intakeId)?.status)
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
    }

    /**
     * На карточке записывают **набранное**: человек выпил одну таблетку вместо двух, и в историю
     * идёт одна. Плановое стоит в поле готовым ответом, но правилом не является (PLAN D5).
     */
    @Test
    fun theCardWritesTheAmountThePersonTyped() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        val model = cardModel(courseId, intakeId)

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.unit != null }
            assertEquals("2", shown.form.amount)
            model.edit(shown.form.copy(amount = "1"))
            state.awaiting(PATIENTLY) { it.form.amount == "1" }
            model.confirm()
            state.awaiting(PATIENTLY) { it.isDone }
        }

        assertEquals(tablets("19"), database.packageRepository().find(PACK)?.quantity)
    }

    /**
     * Вопрос виден на карточке списком, и до ответа не записано ничего; «всё равно принял» —
     * тот же вызов с подтверждением, и он пишет (PLAN D6).
     */
    @Test
    fun theQuestionIsAskedOnTheCardAndAnsweringItWrites() = runBlocking {
        val courseId = started(expiresOn = LocalDate.of(2027, 3, 1))
        val intakeId = firstIntake(courseId).id
        val model = cardModel(courseId, intakeId)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.unit != null }
            model.confirm()
            val asked = state.awaiting(PATIENTLY) { it.questions.isNotEmpty() }
            assertEquals(IntakeStatus.PLANNED, database.intakeRepository().find(intakeId)?.status)
            assertTrue(asked.canAnswer)
            model.confirm(acknowledged = true)
            state.awaiting(PATIENTLY) { it.isDone }
        }

        assertEquals(IntakeStatus.TAKEN, database.intakeRepository().find(intakeId)?.status)
    }

    /** Отвеченный пункт карточка показывает, а не спрашивает: ответ на приём даётся один раз. */
    @Test
    fun anAnsweredItemIsShownNotAsked() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        scenarios.intakeConfirmation.confirm(intakeId, PACK, dose("2"), now)
        val model = cardModel(courseId, intakeId)

        val shown = watching(model.state) { it.awaiting(PATIENTLY) { state -> state.unit != null } }

        assertFalse(shown.canAnswer)
        assertNotNull(shown.answeredAt)
    }

    /** Готовая страница; пока чтение не пришло, спрашивать у состояния нечего. */
    private fun ScreenState<DayPagePresentationDTO>.ready(): DayPagePresentationDTO? =
        (this as? ScreenState.Ready)?.value

    private companion object {
        /** Столько ждём чтения: между действием и состоянием стоят сценарий и потоки Room. */
        val PATIENTLY: Duration = 15.seconds
    }
}
