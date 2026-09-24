package com.kert0n.medapp.feature.stories

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseAmendment
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.Mechanisms
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозные истории лечения без экранов (PLAN J2.2, J2.9): черновик с заметкой → покупка → два
 * источника → распределение по приёмам → уведомления механизмом → приёмы и пропуски → завершение →
 * история; и изменение лечения тем же эпизодом. Утверждения — на чтениях экранов (U2).
 */
@RunWith(AndroidJUnit4::class)
class CourseStoriesTest {

    private lateinit var database: MedAppDatabase
    private val day1: LocalDate = LocalDate.of(2027, 3, 10)

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() = database.close()

    /** Часы на [day] в [hour]:[minute] по Москве — зона курса. */
    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Instant = day.atTime(hour, minute).atZone(MOSCOW).toInstant()

    private suspend fun bought(scenarios: Scenarios, name: String, quantity: String): Uuid =
        (scenarios.packageAdding.add(HOME_KIT, PackageFacts(PackageSharedFacts(name, form = TABLET_FORM)), tablets(quantity)) as PackageAdding.Outcome.Added).packageId

    private suspend fun intakes(course: Uuid): List<CourseIntake> =
        database.intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    private suspend fun planned(course: Uuid): List<CourseIntake> = intakes(course).filter { it.status == IntakeStatus.PLANNED }

    /**
     * **J2.2 Курс от заметки.** Записал у врача → купил две пачки → по две таблетки в 21:00, четыре
     * дозы: три из первой пачки, одна из второй → напоминание пришло будильником в свой миг →
     * принял, пропустил, не ответил, принял, принял, принял → лечение закончено, запись эпизода
     * есть, плана нет, обязательства сняты, история — все пункты со статусами.
     */
    @Test
    fun aCourseFromTheDoctorsNoteToItsEnd() = runBlocking {
        val morning = Scenarios(database, at(day1, 10))
        val note = morning.courseDrafting.create("Ибупрофен", note = "врач: по 2 таблетки вечером, 4 дня")
        assertEquals("врач: по 2 таблетки вечером, 4 дня", database.courseRepository().observeDrafts().first().single().note)

        val first = bought(morning, "Ибупрофен 200", "6")
        val second = bought(morning, "Ибупрофен 400", "10")
        val draft = (morning.courseDrafting.edit(
            note.id, note.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = day1, times = listOf(LocalTime.of(21, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(first, Doses(3)),
                CourseDrafting.Edit.Attach(second, Doses(1))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        morning.courseActivation.activate(draft.id, draft.revision)
        val course = draft.id

        // Экран 12: план на день — первый пункт сегодня в 21:00 из первой пачки; распределение по
        // порядку расходования: три из первой, четвёртая из второй.
        val plan = planned(course)
        assertEquals(at(day1, 21), plan.first().plannedAt)
        assertEquals(listOf(first, first, first, second), plan.take(4).map { it.plannedPackage?.id })

        // Уведомление приходит своим будильником: точная постановка — на 21:00, а в 21:00 владелец
        // доставки показывает напоминание о первом пункте.
        Mechanisms(morning, morning.now).use { mechanisms ->
            mechanisms.await("точная постановка на первый пункт") { morning.reminders.exactAt == plan.first().plannedAt }
        }
        val evening = Scenarios(database, at(day1, 21))
        Mechanisms(evening, evening.now).use { mechanisms ->
            mechanisms.await("напоминание о первом пункте показано") {
                evening.notifier.shown.any { it.key == NotificationKey.intake(plan.first().id, NotificationKind.INTAKE_DUE) }
            }
        }

        // День 1 — принял; день 2 — пропустил; день 3 — не ответил; дни 4–6 — принял.
        evening.intakeConfirmation.confirm(plan[0].id, first, dose("2"), evening.now).confirmed()
        val day2 = Scenarios(database, at(day1.plusDays(1), 21, 5))
        assertEquals(IntakeDeclining.Outcome.Declined, day2.intakeDeclining.decline(planned(course).first().id, day2.now))
        val day4 = Scenarios(database, at(day1.plusDays(3), 21, 5))
        day4.dailyRound.run()
        val missedByNoAnswer = intakes(course).first { it.plannedAt == at(day1.plusDays(2), 21) }
        assertEquals(IntakeStatus.MISSED, missedByNoAnswer.status)
        assertTrue(day4.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_MISSED)).any { it.key == NotificationKey.intake(missedByNoAnswer.id, NotificationKind.INTAKE_MISSED) })
        day4.intakeConfirmation.confirm(planned(course).first().id, first, dose("2"), day4.now).confirmed()
        val day5 = Scenarios(database, at(day1.plusDays(4), 21, 5))
        day5.intakeConfirmation.confirm(planned(course).first().id, first, dose("2"), day5.now).confirmed()
        val day6 = Scenarios(database, at(day1.plusDays(5), 21, 5))
        val last = planned(course).first()
        assertEquals(second, last.plannedPackage?.id)
        day6.intakeConfirmation.confirm(last.id, second, dose("2"), day6.now).confirmed()

        // Лечение закончено: плана нет, запись эпизода закрыта, обязательств не осталось.
        assertNull(database.courseRepository().findPlan(course))
        val record = database.courseRepository().observeRecords().first().single { it.id == course }
        assertEquals(CourseRecord.Outcome.COMPLETED, record.outcome)
        assertEquals(0, day6.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).count { it.state == com.kert0n.medapp.domain.notification.Reminder.State.DUE })
        // Экран 19: история — четыре приёма и два пропуска; пачки первой, первой, первой и второй.
        val history = database.intakeRepository().observeOfCourse(course).first().filterIsInstance<IntakeProjection.Scheduled>().sortedBy { it.slot.at }
        assertEquals(listOf(IntakeStatus.TAKEN, IntakeStatus.MISSED, IntakeStatus.MISSED, IntakeStatus.TAKEN, IntakeStatus.TAKEN, IntakeStatus.TAKEN), history.map { it.status })
        assertEquals(listOf(first, first, first, second), history.filter { it.status == IntakeStatus.TAKEN }.map { it.taken?.pkg?.id })
        // Первая коробка выпита до конца — пустой коробки не бывает, строки нет; во второй осталось восемь.
        assertNull(database.packageRepository().observe(first).first())
        assertEquals(tablets("8"), requireNotNull(database.packageRepository().observe(second).first()).quantity)
    }

    /**
     * **J2.9 Изменение лечения.** Подсказка упаковки меняется независимо от дозы курса; врач сменил
     * дозу — тот же эпизод с новой дозой: принятые пункты с прежней плановой дозой, будущие — с
     * новой; редакция выросла; обеспечение считается по новой дозе.
     */
    @Test
    fun changingTheTreatmentKeepsTheEpisodeAndTheHistory() = runTest {
        val start = Scenarios(database, at(day1, 10))
        val pkg = bought(start, "Парацетамол", "20")
        val note = start.courseDrafting.create("Парацетамол")
        val draft = (start.courseDrafting.edit(
            note.id, note.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = day1, times = listOf(LocalTime.of(9, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(pkg, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        start.courseActivation.activate(draft.id, draft.revision)
        val course = draft.id
        for (day in 0 until 3) {
            val morning = Scenarios(database, at(day1.plusDays(day.toLong()), 9, 5))
            morning.intakeConfirmation.confirm(planned(course).first().id, pkg, dose("1"), morning.now).confirmed()
        }

        // Подсказка коробки — не доза курса.
        val later = Scenarios(database, at(day1.plusDays(3), 12))
        later.packageDescribing.describe(pkg, requireNotNull(database.packageRepository().find(pkg)).facts.copy(defaultIntakeAmount = dose("3")))
        assertEquals(dose("1"), requireNotNull(database.courseRepository().findPlan(course)).dose)

        val before = requireNotNull(database.courseRepository().findPlan(course))
        val amended = later.courseAmendment.amend(course, before.revision, listOf(CourseAmendment.Change.SetDose(dose("2"))))
        assertTrue("изменение не принято: $amended", amended is CourseAmendment.Outcome.Amended)

        val after = requireNotNull(database.courseRepository().findPlan(course))
        assertEquals(course, after.id)
        assertEquals(before.revision.next(), after.revision)
        assertEquals(dose("2"), after.dose)
        val record = requireNotNull(database.courseRepository().observeRecord(course).first())
        assertEquals(dose("2"), record.prescription.dose)
        assertTrue(record.isOpen)
        val history = database.intakeRepository().observeOfCourse(course).first().filterIsInstance<IntakeProjection.Scheduled>().sortedBy { it.slot.at }
        assertEquals(listOf(dose("1"), dose("1"), dose("1")), history.filter { it.status == IntakeStatus.TAKEN }.map { it.plannedAmount })
        // Сегодняшний утренний пункт уже начался и остаётся со своей дозой; перестраиваются будущие (J1).
        val future = history.filter { it.status == IntakeStatus.PLANNED && it.slot.at.isAfter(later.now) }
        assertTrue(future.isNotEmpty())
        assertTrue(future.all { it.plannedAmount == dose("2") })
        // Обеспечение — по новой дозе: 17 таблеток на три дозы по две — хватает, выделение не выше потребности.
        val coverage = requireNotNull(database.courseRepository().observeCoverage(course).first())
        assertEquals(Doses(3), coverage.requiredDoses)
        assertEquals(Doses(3), coverage.coveredDoses)
    }
}
