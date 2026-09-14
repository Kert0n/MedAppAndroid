package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
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
 * Ответ из шторки идёт тем же путём, что с экрана (PLAN D8, C1): «Принял» записывает приём один раз
 * и гасит напоминание; вопрос и отказ открывают приложение и не пишут; «Отложить» сдвигает только
 * будильник; отвеченный с экрана пункт напоминание тоже гасит.
 */
@RunWith(AndroidJUnit4::class)
class ReminderAnsweringTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T06:00:00Z") // 09:00 МСК, момент первого пункта
    private val today = now.atZone(ZoneOffset.UTC).toLocalDate()

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun treated(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun first(id: Uuid): CourseIntake =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }

    private fun reminderKey(intake: CourseIntake) = NotificationKey.intake(intake.id, NotificationKind.INTAKE_DUE)

    /** Двойное «Принял» — один приём и одна команда; напоминание погашено, будильник снят. */
    @Test
    fun takeWritesOnceAndWithdrawsTheReminder() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminders.schedule(reminderKey(intake), intake.plannedAt)

        assertEquals(ReminderAnswering.Response.Done, scenarios.reminderAnswering.take(intake.id))
        assertEquals(ReminderAnswering.Response.Done, scenarios.reminderAnswering.take(intake.id))

        assertEquals(IntakeStatus.TAKEN, requireNotNull(database.intakeRepository().find(intake.id)).status)
        assertEquals(tablets("18"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertEquals(0, database.syncOperations().all().size) // местная полка — команд нет
        assertTrue(scenarios.notifier.dismissed.contains(reminderKey(intake)))
        assertNull(scenarios.reminders.scheduled[reminderKey(intake)])
    }

    /** Просроченная коробка: из шторки приём не записывается — открывается приложение (предупреждение не обходится). */
    @Test
    fun takeOnAnExpiredBoxOpensTheAppAndWritesNothing() = runTest {
        val id = treated()
        database.packageRepository().describe(PACK, factsOf(pack(quantity = tablets("20"), form = TABLET_FORM)).copy(expiresOn = ExpiryDate(today.minusDays(1))))
        val intake = first(id)

        assertEquals(ReminderAnswering.Response.OpenApp, scenarios.reminderAnswering.take(intake.id))

        assertEquals(IntakeStatus.PLANNED, requireNotNull(database.intakeRepository().find(intake.id)).status)
        assertEquals(tablets("20"), requireNotNull(database.packageRepository().find(PACK)).quantity)
    }

    /** По пункту отменённого курса «Принял» открывает приложение и ничего не пишет. */
    @Test
    fun takeOnACancelledCourseOpensTheApp() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.courseCancellation.cancel(id)

        assertEquals(ReminderAnswering.Response.OpenApp, scenarios.reminderAnswering.take(intake.id))
        assertEquals(IntakeStatus.CANCELLED, requireNotNull(database.intakeRepository().find(intake.id)).status)
    }

    /** «Отложить» сдвигает будильник на snoozeMinutes; plannedAt и статус пункта прежние. */
    @Test
    fun snoozeMovesOnlyTheAlarm() = runTest {
        val id = treated()
        val intake = first(id)

        val response = scenarios.reminderAnswering.snooze(intake.id) as ReminderAnswering.Response.Snoozed

        assertEquals(now.plusSeconds(15 * 60), response.at)
        assertEquals(response.at, scenarios.reminders.scheduled[reminderKey(intake)])
        val same = first(id)
        assertEquals(intake.plannedAt, same.plannedAt)
        assertEquals(IntakeStatus.PLANNED, same.status)
    }

    /** «Пропустить» — отказ человека: пункт пропущен, напоминание погашено. */
    @Test
    fun skipDeclinesAndWithdraws() = runTest {
        val id = treated()
        val intake = first(id)

        assertEquals(ReminderAnswering.Response.Done, scenarios.reminderAnswering.skip(intake.id))

        assertEquals(IntakeStatus.MISSED, requireNotNull(database.intakeRepository().find(intake.id)).status)
        assertTrue(scenarios.notifier.dismissed.contains(reminderKey(intake)))
    }
}
