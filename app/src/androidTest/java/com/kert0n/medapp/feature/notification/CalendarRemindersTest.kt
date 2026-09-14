package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseAmendment
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Календарь сам обещает и сам снимает обещания — той же транзакцией, что и меняет пункты (PLAN D8).
 * До этого будильники ставил только проход дня, и лечение, заведённое между проходами, о первом
 * приёме не напоминало, а перестройка расписания оставляла будильники на удалённых пунктах.
 */
@RunWith(AndroidJUnit4::class)
class CalendarRemindersTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val start = LocalDate.of(2027, 3, 10)
    private val now: Instant = Instant.parse("2027-03-10T05:00:00Z") // 08:00 МСК, до первого приёма

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("40"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    /** Лечение с 10 марта раз в день в 09:00 МСК, [doses] доз из [PACK]. */
    private suspend fun treated(doses: Int = 5): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(doses)),
                CourseDrafting.Edit.Attach(PACK, Doses(doses))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun planned(id: Uuid) =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().filter { it.status == IntakeStatus.PLANNED }

    private suspend fun owed(kind: NotificationKind) =
        scenarios.reminderStore.ofKinds(listOf(kind)).associateBy { Uuid.parse(it.key.subject) }

    /**
     * Анна завела лечение и закрыла приложение: обещание напомнить о первом приёме уже есть, и
     * будильник стоит на его момент — ждать прохода дня не нужно.
     */
    @Test
    fun aNewCourseOwesItsRemindersRightAway() = runTest {
        val id = treated()

        val intakes = planned(id)
        assertEquals(intakes.map { it.id }.toSet(), owed(NotificationKind.INTAKE_DUE).keys)
        assertEquals(intakes.map { it.plannedAt }.toSet(), owed(NotificationKind.INTAKE_DUE).values.map { it.dueAt }.toSet())

        scenarios.reminderOutbox.pass()
        assertEquals(intakes.minOf { it.plannedAt }, scenarios.reminders.wakeAt)
        assertTrue(scenarios.reminders.exact)
    }

    /**
     * Правка лечения пересоздаёт будущие пункты с новыми идентификаторами. Обещания едут за ними:
     * у каждого нынешнего пункта своё, и ни одного — на пункт, которого больше нет (красная
     * проверка: не трогать обязательства в `prune`/`extend` — остались бы обещания-сироты).
     */
    @Test
    fun amendingTheCourseMovesTheObligationsWithTheIntakes() = runTest {
        val id = treated()
        val before = planned(id).map { it.id }.toSet()
        val revision = requireNotNull(database.courseRepository().findPlan(id)).revision

        val outcome = scenarios.courseAmendment.amend(id, revision, listOf(CourseAmendment.Change.SetDose(dose("1"))))

        assertTrue("$outcome", outcome is CourseAmendment.Outcome.Amended)
        val after = planned(id).map { it.id }.toSet()
        assertEquals(emptySet<Uuid>(), before intersect after) // пункты действительно пересозданы
        // Обещания ушедших пунктов отозваны, и владелец доставки их забывает; остаются нынешние.
        scenarios.reminderOutbox.pass()
        assertEquals(after, owed(NotificationKind.INTAKE_DUE).keys)
    }

    /**
     * Пропуск, о котором не удалось сказать, не теряется: обязательство остаётся невыполненным и
     * доходит следующим проходом. Прежде повод жил ровно один проход и исчезал вместе с ним.
     */
    @Test
    fun anUnsaidMissIsStillOwed() = runTest {
        treated()
        val nextMorning = Scenarios(database, Instant.parse("2027-03-11T05:00:00Z"), notifier = scenarios.notifier)
        nextMorning.notifier.allowed = false

        nextMorning.dailyRound.run()
        assertEquals(0, nextMorning.reminderOutbox.pass().shown)
        val owedMiss = nextMorning.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_MISSED))
        assertTrue(owedMiss.isNotEmpty())
        assertTrue(owedMiss.all { it.state == Reminder.State.DUE })

        nextMorning.notifier.allowed = true
        nextMorning.reminderOutbox.pass()
        assertEquals(owedMiss.size, nextMorning.notifier.shown.count { it.kind == NotificationKind.INTAKE_MISSED })
    }

    /**
     * `missOverdue` зовут восемь мест, и сказать о пропуске должно каждое — обещание заводит сам
     * `missOverdue`, а не тот, кто его позвал. Здесь проверены два вызывающих, не имеющих к
     * проходу дня отношения: отказ от пункта и правка лечения.
     */
    @Test
    fun everyPlaceThatMissesAnIntakeOwesTheNotice() = runTest {
        val id = treated()
        val tomorrow = Scenarios(database, Instant.parse("2027-03-11T05:00:00Z"), notifier = scenarios.notifier)
        val today = planned(id).first { it.slot.localDate == start.plusDays(1) }

        // Отказ от сегодняшнего пункта: прошлое приводится в порядок тем же сценарием (F4).
        tomorrow.intakeDeclining.decline(today.id, Instant.parse("2027-03-11T06:00:00Z"))

        val yesterday = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>()
            .single { it.slot.localDate == start }
        assertEquals(IntakeStatus.MISSED, yesterday.status)
        assertEquals(
            setOf(yesterday.id),
            tomorrow.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_MISSED)).map { Uuid.parse(it.key.subject) }.toSet()
        )
        // А об отказе человека не сообщают: он решил сам (D6, D8).
        assertNull(tomorrow.reminderStore.find(NotificationKey.intake(today.id, NotificationKind.INTAKE_MISSED)))
        assertEquals(Reminder.State.WITHDRAWN, requireNotNull(tomorrow.reminderStore.find(NotificationKey.intake(today.id, NotificationKind.INTAKE_DUE))).state)
    }

    /** Второй вызывающий: правка лечения тоже приводит прошлое в порядок — и тоже обещает сказать. */
    @Test
    fun amendingTheCourseAlsoOwesTheNoticeForWhatItMissed() = runTest {
        val id = treated()
        val tomorrow = Scenarios(database, Instant.parse("2027-03-11T05:00:00Z"), notifier = scenarios.notifier)
        val revision = requireNotNull(database.courseRepository().findPlan(id)).revision

        tomorrow.courseAmendment.amend(id, revision, listOf(CourseAmendment.Change.SetDose(dose("1"))))

        val yesterday = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>()
            .single { it.slot.localDate == start }
        assertEquals(IntakeStatus.MISSED, yesterday.status)
        assertEquals(
            setOf(yesterday.id),
            tomorrow.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_MISSED)).map { Uuid.parse(it.key.subject) }.toSet()
        )
    }
}
