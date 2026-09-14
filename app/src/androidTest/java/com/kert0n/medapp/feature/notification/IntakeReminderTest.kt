package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.FakeNotifier
import com.kert0n.medapp.fixture.FakeReminders
import com.kert0n.medapp.fixture.FakeSettings
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.server.NotificationLogRoomRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
 * Напоминание о приёме: будильники — на ближайшие 36 часов по плановым пунктам, отвеченный пункт
 * не напоминает, напоминание показывается каждым будильником, остальное — один раз (PLAN D8, C1).
 */
@RunWith(AndroidJUnit4::class)
class IntakeReminderTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T05:00:00Z") // 08:00 МСК 10 марта
    private val notifier = FakeNotifier()
    private val reminders = FakeReminders()
    private val settings = FakeSettings()
    private lateinit var planning: NotificationPlanning
    private lateinit var delivery: NotificationDelivery

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        planning = NotificationPlanning(database.intakeRepository(), database.packageRepository(), database.courseRepository(), settings)
        delivery = NotificationDelivery(notifier, reminders, NotificationLogRoomRepository(database.notificationLog()), Clock.fixed(now, ZoneOffset.UTC))
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    /** Лечение дважды в день с сегодняшнего дня, 10 доз — окно календаря даёт пункты далеко вперёд. */
    private suspend fun treated(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10), times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(10))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun intakes(id: Uuid) = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    @Test
    fun remindersCoverThirtySixHoursAndSkipAnsweredIntakes() = runTest {
        val id = treated()
        val all = intakes(id)
        scenarios.intakeConfirmation.confirm(all[0].id, PACK, dose("2"), now).confirmed()

        val due = planning.remindersDue(now)
        delivery.arm(due)

        // 36 часов от 08:00 МСК — до 20:00 завтра: 21:00 сегодня и 09:00 завтра входят, 21:00 завтра —
        // нет; принятый в 09:00 сегодня не напоминает.
        assertEquals(listOf(all[1].id, all[2].id), due.map { (it.target as com.kert0n.medapp.domain.notification.NotificationTarget.Intake).intakeId })
        assertTrue(due.all { it.exact && it.actions == listOf(NotificationAction.TAKE, NotificationAction.SKIP, NotificationAction.SNOOZE) })
        assertEquals(due.associate { it.key to it.dueAt }, reminders.scheduled)
        assertNull(planning.reminderFor(all[0].id))
        assertEquals(all[1].id, (planning.reminderFor(all[1].id)?.target as com.kert0n.medapp.domain.notification.NotificationTarget.Intake).intakeId)
    }

    @Test
    fun disabledRemindersPlanNothing() = runTest {
        treated()
        settings.settings = NotificationSettings(intakeRemindersEnabled = false)
        assertEquals(emptyList<Any>(), planning.remindersDue(now))
    }

    /** Напоминание о приёме показывается каждым будильником (отложенное придёт снова); остальное — один раз по журналу. */
    @Test
    fun theReminderRepeatsButEverythingElseIsShownOnce() = runTest {
        val id = treated()
        val reminder = requireNotNull(planning.reminderFor(intakes(id)[1].id))
        val digest = reminder.copy(key = NotificationKey.digest(LocalDate.of(2027, 3, 10)), target = com.kert0n.medapp.domain.notification.NotificationTarget.DayPlan(LocalDate.of(2027, 3, 10)), actions = emptyList())

        assertEquals(2, delivery.deliver(listOf(reminder, digest)))
        assertEquals(1, delivery.deliver(listOf(reminder, digest)))

        assertEquals(3, notifier.shown.size)
        assertEquals(1, notifier.shown.count { it.kind == NotificationKind.DAILY_DIGEST })
    }

    /** Без разрешения показа нет — и в журнал ничего не пишется: разрешат — покажем. */
    @Test
    fun aRefusedShowingIsNotRemembered() = runTest {
        val id = treated()
        val digest = requireNotNull(planning.reminderFor(intakes(id)[1].id)).copy(key = NotificationKey.digest(LocalDate.of(2027, 3, 10)))
        notifier.allowed = false
        assertEquals(0, delivery.deliver(listOf(digest)))
        notifier.allowed = true
        assertEquals(1, delivery.deliver(listOf(digest)))
    }
}
