package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проход дня (PLAN D8): вчерашний неответ — пропуск и уведомление о нём, будильники на сегодняшние
 * пункты, сводка при событиях и молчание без них; повтор прохода ничего не дублирует.
 */
@RunWith(AndroidJUnit4::class)
class DailyRoundTest {

    private lateinit var database: MedAppDatabase
    private val start = LocalDate.of(2027, 3, 10)

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM, expiresOn = ExpiryDate(start.plusDays(2))))
    }

    @After
    fun tearDown() = database.close()

    /** Лечение с 10 марта раз в день в 09:00 МСК, 5 доз из [PACK]. */
    private suspend fun treated(scenarios: Scenarios): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    @Test
    fun theRoundMarksYesterdayMissedArmsTodayAndDigestsOnce() = runTest {
        val id = treated(Scenarios(database, Instant.parse("2027-03-10T05:00:00Z")))
        // Назавтра в 08:00 МСК: вчерашний пункт без ответа, коробка годна до 12-го — за день источнику.
        val nextMorning = Scenarios(database, Instant.parse("2027-03-11T05:00:00Z"))

        val report = nextMorning.dailyRound.run()

        val byDay = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().associate { it.slot.localDate to it }
        assertEquals(IntakeStatus.MISSED, byDay.getValue(start).status)
        assertEquals(1, report.missed)
        // Будильники — на сегодняшний пункт и завтрашний (36 часов), не на вчерашний.
        assertEquals(setOf(byDay.getValue(start.plusDays(1)).id, byDay.getValue(start.plusDays(2)).id), nextMorning.reminders.scheduled.keys.map { Uuid.parse(it.subject) }.toSet())
        // Показано: пропуск, срок источника за день, сводка.
        assertEquals(setOf(NotificationKind.INTAKE_MISSED, NotificationKind.EXPIRY_SOURCE_1D, NotificationKind.DAILY_DIGEST), nextMorning.notifier.shown.map { it.kind }.toSet())
        assertEquals(NotificationKind.DAILY_DIGEST, nextMorning.notifier.shown.last().kind)
        assertEquals(3, report.shown)

        // Повтор того же утра: пропусков больше нет, показанное не повторяется, будильники те же.
        val again = nextMorning.dailyRound.run()
        assertEquals(0, again.missed)
        assertEquals(0, again.shown)
        assertEquals(3, nextMorning.notifier.shown.size)
    }

    /** Без лечения и с годными коробками проход молчит и в журнал не пишет — счастливый путь. */
    @Test
    fun aQuietDayShowsNothing() = runTest {
        val quiet = Scenarios(database, Instant.parse("2027-03-01T05:00:00Z"))

        val report = quiet.dailyRound.run()

        assertEquals(DailyRound.Report(missed = 0, reminders = 0, shown = 0), report)
        assertEquals(emptyList<Any>(), quiet.notifier.shown)
        assertEquals(0, database.notificationLog().ofKind(NotificationKind.DAILY_DIGEST.name).size)
    }
}
