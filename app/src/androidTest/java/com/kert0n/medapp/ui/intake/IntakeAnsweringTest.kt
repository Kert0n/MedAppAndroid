package com.kert0n.medapp.ui.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.AllAllowed
import com.kert0n.medapp.fixture.OTHER_PACK
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
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
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

    private fun dayModel(clock: Clock = this.clock) = DayPlanViewModel(
        today = Today(clock, QuietClock),
        planning = DayPlanning(Today(clock, QuietClock), database.reportRepository()),
        confirmation = scenarios.intakeConfirmation,
        declining = scenarios.intakeDeclining,
        devicePermissions = AllAllowed,
        readiness = AllAllowed,
        clock = clock
    ).also { opened += it }

    private fun cardModel(
        intakeId: Uuid,
        freshening: com.kert0n.medapp.feature.operation.Freshening =
            com.kert0n.medapp.fixture.offlineFreshening(database.packageRepository(), clock)
    ) = IntakeCardViewModel(
        confirmation = scenarios.intakeConfirmation,
        declining = scenarios.intakeDeclining,
        freshening = freshening,
        vocabulary = VocabularyRoomRepository(database.vocabulary()),
        today = Today(clock, QuietClock),
        clock = clock,
        courses = database.courseRepository(),
        intakes = database.intakeRepository(),
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
    /** То же лечение, но коробок у него две: вторая — та, из которой человек возьмёт на самом деле. */
    private suspend fun startedWithTwoSources(): Uuid {
        database.packageRepository().add(
            pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("10"), form = TABLET_FORM)
        )
        val courseId = started()
        val plan = requireNotNull(database.courseRepository().findPlan(courseId))
        scenarios.sourceEditing.save(
            courseId, plan.revision,
            listOf(
                SourceEditing.Source(PACK, Doses(2)),
                SourceEditing.Source(OTHER_PACK, Doses(2))
            )
        )
        return courseId
    }

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
                state.ready()?.items.orEmpty().none { it.canConfirm }
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
            page.awaiting(PATIENTLY) { state -> state.ready()?.items.orEmpty().none { it.canConfirm } }
        }

        assertEquals(tablets("18"), database.packageRepository().find(PACK)?.quantity)
    }

    /**
     * Быстрый ответ по просроченной коробке **пишет сразу**: вопроса нет, срок человеку показан
     * (PLAN C1 «Просроченная пачка», поправка 2026-09-16). Отправляй его на карточку — и одним
     * нажатием приём было бы не записать.
     */
    @Test
    fun anExpiredBoxIsTakenFromTheDayAtOnce(): Unit = runBlocking {
        val courseId = started(expiresOn = LocalDate.of(2027, 3, 1))
        val intakeId = firstIntake(courseId).id
        val model = dayModel()

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { it.ready()?.items?.isNotEmpty() == true }
            model.confirm(intakeId)
            page.awaiting(PATIENTLY) { state -> state.ready()?.items.orEmpty().none { it.canConfirm } }
        }

        assertEquals(IntakeStatus.TAKEN, database.intakeRepository().find(intakeId)?.status)
    }

    /**
     * На карточке записывают **набранное**: человек выпил одну таблетку вместо двух, и в историю
     * идёт одна. Плановое стоит в поле готовым ответом, но правилом не является (PLAN D5).
     */
    @Test
    fun theCardWritesTheAmountThePersonTyped() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        val model = cardModel(intakeId)

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
     * Принять можно из **другой коробки лечения**: «беру из этой пачки» решается в момент записи,
     * а не при постройке плана (PLAN D6). Расход идёт из выбранной, плановая остаётся целой.
     */
    @Test
    fun theIntakeIsTakenFromTheChosenSourceOfTheCourse() = runBlocking {
        val courseId = startedWithTwoSources()
        val intakeId = firstIntake(courseId).id
        val model = cardModel(intakeId)

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.sources.size == 2 }
            model.edit(shown.form.copy(packageId = OTHER_PACK))
            state.awaiting(PATIENTLY) { it.packageId == OTHER_PACK }
            model.confirm()
            state.awaiting(PATIENTLY) { it.isDone }
        }

        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(tablets("8"), database.packageRepository().find(OTHER_PACK)?.quantity)
    }

    /**
     * **Выбранную коробку не подменяют плановой** (CodeRabbit 4030083072). Человек выбрал
     * «Ибупрофен», а пока он набирал время, коробку отключили от лечения на другом экране. Карточка
     * молча вернула в выбор плановый «Нурофен», и «Принять» списало бы из коробки, которую человек не
     * выбирал. Выбор пропадает словами поля — пустым, — и записывать нечего, пока не выберут заново.
     */
    @Test
    fun aChosenBoxThatLeftTheCourseIsNotSwappedForThePlannedOne() = runBlocking {
        val courseId = startedWithTwoSources()
        val intakeId = firstIntake(courseId).id
        val model = cardModel(intakeId)

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.sources.size == 2 }
            model.edit(shown.form.copy(packageId = OTHER_PACK))
            state.awaiting(PATIENTLY) { it.packageId == OTHER_PACK }

            val plan = requireNotNull(database.courseRepository().findPlan(courseId))
            scenarios.sourceEditing.save(courseId, plan.revision, listOf(SourceEditing.Source(PACK, Doses(4))))
            state.awaiting(PATIENTLY) { it.sources.size == 1 }
            model.confirm()
            // Записи ждать нечего: даём сценарию время, за которое он записал бы.
            kotlinx.coroutines.delay(1_000)
            assertEquals("выбор подменён плановой коробкой", null, state.value.packageId)
        }

        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(IntakeStatus.PLANNED, database.intakeRepository().find(intakeId)?.status)
    }

    /**
     * Отказ — решение: доза считается пропущенной, а коробка остаётся целой. Расхода не было, и
     * списывать нечего (PLAN D6); молчание тем и отличается от отказа, что после него пункт всё
     * ещё ждёт ответа.
     */
    @Test
    fun aRefusalMarksTheDoseMissedAndSpendsNothing() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        val model = dayModel()

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { it.ready()?.items?.isNotEmpty() == true }
            model.decline(intakeId)
            page.awaiting(PATIENTLY) { state ->
                state.ready()?.items.orEmpty().any { it.state == DayItemPresentationDTO.State.MISSED }
            }
        }

        assertEquals(IntakeStatus.MISSED, database.intakeRepository().find(intakeId)?.status)
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
    }

    /** Отвеченный пункт карточка показывает, а не спрашивает: ответ на приём даётся один раз. */
    @Test
    fun anAnsweredItemIsShownNotAsked() = runBlocking {
        val courseId = started()
        val intakeId = firstIntake(courseId).id
        scenarios.intakeConfirmation.confirm(intakeId, PACK, dose("2"), now)
        val model = cardModel(intakeId)

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


    /**
     * Карточка пункта перечитывает коробки-источники своего лечения, как только знает, какие они, и
     * пока сервер не ответил, ждёт: пачку выбирают по свежему числу (PLAN E4). Какой полки коробка
     * и знает ли её сервер, здесь подменено: предмет проверки — ожидание карточки, а не полка.
     */
    @Test
    fun theCardWaitsForItsSourcesFromTheServer() = runBlocking {
        val intakeId = firstIntake(started()).id
        val server = com.kert0n.medapp.fixture.RereadingServer(clock)
        server.hold()
        val known = object : com.kert0n.medapp.storage.pack.PackageStorageRepository by database.packageRepository() {
            override suspend fun answersToServer(packageId: Uuid): Boolean = true
        }
        val model = cardModel(intakeId, com.kert0n.medapp.fixture.onlineFreshening(server, known, clock))

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.title.isNotEmpty() }
            kotlinx.coroutines.withTimeout(5_000) { while (server.asked.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(true, state.value.isLoading)
            server.release()
            state.awaiting(PATIENTLY) { !it.isLoading }
        }

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }
}
