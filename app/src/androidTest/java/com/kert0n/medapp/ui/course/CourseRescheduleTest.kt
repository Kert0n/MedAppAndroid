package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.CourseFormViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Через день» сегодня не выражается (issue #48), и человек обходится сменой расписания на ходу:
 * выпил в воскресенье и переделал лечение на вторник-четверг-субботу. Проверка о том, что после
 * такой правки **приходит ровно то, что он назначил**, — и ничего из прежней сетки.
 *
 * Над Room и настоящими сценариями: пункты строит календарь, обещания напомнить заводит и снимает
 * он же, и подделка сказала бы о них то, чего не было.
 */
@RunWith(AndroidJUnit4::class)
class CourseRescheduleTest {

    private lateinit var database: MedAppDatabase

    /** Воскресенье 7 марта 2027 года: 09:00 по Москве — 06:00 UTC. */
    private val sunday: LocalDate = LocalDate.of(2027, 3, 7)
    private val beforeTheDose: Instant = Instant.parse("2027-03-07T05:00:00Z")
    private val afterTheDose: Instant = Instant.parse("2027-03-07T06:10:00Z")
    private val noon: Instant = Instant.parse("2027-03-07T09:00:00Z")

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        database.packageRepository()
            .add(pack(id = PACK, name = "Нурофен", quantity = tablets("40"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun scenarios(at: Instant) = Scenarios(database, at)

    private fun model(courseId: Uuid, at: Instant): CourseFormViewModel {
        val scenarios = scenarios(at)
        return CourseFormViewModel(
            drafting = scenarios.courseDrafting,
            activation = scenarios.courseActivation,
            amendment = scenarios.courseAmendment,
            renaming = scenarios.courseRenaming,
            courses = database.courseRepository(),
            vocabulary = FakeVocabulary(),
            courseId = courseId
        ).also { opened += it }
    }

    /** Ежедневное лечение с воскресенья: шесть приёмов в девять утра. */
    private suspend fun startedDaily(): Uuid {
        val scenarios = scenarios(beforeTheDose)
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = sunday, times = listOf(LocalTime.of(9, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    private suspend fun itemsOf(id: Uuid) = database.intakeRepository().observeOfCourse(id).first()
        .filterIsInstance<IntakeProjection.Scheduled>()

    /** Дни, на которые стоят ждущие ответа пункты. */
    private suspend fun plannedDays(id: Uuid): List<LocalDate> = itemsOf(id)
        .filter { it.status == IntakeStatus.PLANNED }
        .map { it.slot.localDate }
        .sorted()

    /**
     * Выпил в воскресенье, переделал на вт-чт-сб — и дальше приходит только по этим дням.
     * Воскресный приём остаётся фактом: изменение лечения прошлого не переписывает (PLAN D5).
     */
    @Test
    fun aMidCycleRescheduleKeepsThePastAndMovesOnlyWhatIsAhead() = runBlocking {
        val id = startedDaily()
        val sundayDose = itemsOf(id).first { it.slot.localDate == sunday }
        scenarios(afterTheDose).intakeConfirmation.confirm(sundayDose.id, PACK, dose("2"), afterTheDose)

        val model = model(id, noon)
        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(
                open.form.copy(
                    start = sunday,
                    days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
                )
            )
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        // Воскресный приём цел — с той дозой, какой был назначен.
        val taken = itemsOf(id).first { it.slot.localDate == sunday }
        assertEquals(IntakeStatus.TAKEN, taken.status)
        assertEquals(sundayDose.id, taken.id)

        // Впереди — ровно пять оставшихся приёмов и только по вт-чт-сб.
        assertEquals(
            listOf(
                LocalDate.of(2027, 3, 9), LocalDate.of(2027, 3, 11), LocalDate.of(2027, 3, 13),
                LocalDate.of(2027, 3, 16), LocalDate.of(2027, 3, 18)
            ),
            plannedDays(id)
        )
        assertTrue(plannedDays(id).all { it.dayOfWeek in setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY) })
    }

    /**
     * Напомнит ровно о том, что стоит в плане: обещания снятых пунктов уходят вместе с ними, а на
     * новые заводятся — иначе будильник звонил бы по прежней сетке (PLAN D8).
     */
    @Test
    fun remindersFollowTheNewScheduleAndLeaveTheOldOne() = runBlocking {
        val id = startedDaily()
        val sundayDose = itemsOf(id).first { it.slot.localDate == sunday }
        scenarios(afterTheDose).intakeConfirmation.confirm(sundayDose.id, PACK, dose("2"), afterTheDose)
        val model = model(id, noon)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(
                open.form.copy(
                    start = sunday,
                    days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
                )
            )
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        val planned = itemsOf(id).filter { it.status == IntakeStatus.PLANNED }
        val reminders = scenarios(noon).reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE))
        // Живое обязательство — то, что ещё скажут: снятое остаётся строкой на срок хранения,
        // но звонить по нему нечему (PLAN D8).
        val live = reminders.filter { it.state == Reminder.State.DUE }
        val liveIntakes = live.mapNotNull { (it.target as? NotificationTarget.Intake)?.intakeId }
        assertEquals(planned.map { it.id }.toSet(), liveIntakes.toSet())
        // И момент обещания — момент самого пункта, а не прежней сетки.
        val byId = planned.associateBy { it.id }
        assertTrue(live.all { byId.getValue((it.target as NotificationTarget.Intake).intakeId).slot.at == it.dueAt })
    }

    /**
     * Сегодняшний пункт, чей час уже прошёл, а день ещё нет, перестройка **не уносит**: человек
     * ещё ответит на него — «в девять принял, отвечаю в полдень» (PLAN D5, F4).
     */
    @Test
    fun todaysUnansweredItemSurvivesTheReschedule() = runBlocking {
        val id = startedDaily()

        val model = model(id, noon)
        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(
                open.form.copy(
                    start = sunday,
                    days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
                )
            )
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        assertTrue(plannedDays(id).contains(sunday))
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
