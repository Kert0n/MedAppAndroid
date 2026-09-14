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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val delivered = nextMorning.reminderOutbox.pass()

        val byDay = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().associate { it.slot.localDate to it }
        assertEquals(IntakeStatus.MISSED, byDay.getValue(start).status)
        assertEquals(1, report.missed)
        // Обязательства на приёмы — сегодняшний и завтрашний (36 часов), не вчерашний.
        assertEquals(
            setOf(byDay.getValue(start.plusDays(1)).id, byDay.getValue(start.plusDays(2)).id),
            nextMorning.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { Uuid.parse(it.key.subject) }.toSet()
        )
        // Сказано: пропуск, срок источника за день, сводка. Приёмы ещё впереди — их черёд не настал.
        assertEquals(setOf(NotificationKind.INTAKE_MISSED, NotificationKind.EXPIRY_SOURCE_1D, NotificationKind.DAILY_DIGEST), nextMorning.notifier.shown.map { it.kind }.toSet())
        assertEquals(3, delivered.shown)
        // Будильник один, и он на ближайший невыполненный срок — сегодняшний приём в 09:00 МСК.
        assertEquals(byDay.getValue(start.plusDays(1)).plannedAt, nextMorning.reminders.wakeAt)
        assertTrue(nextMorning.reminders.exact)

        // Повтор того же утра: пропусков больше нет, сказанное не повторяется.
        val again = nextMorning.dailyRound.run()
        assertEquals(0, again.missed)
        assertEquals(0, nextMorning.reminderOutbox.pass().shown)
        assertEquals(3, nextMorning.notifier.shown.size)
    }

    /** Без лечения и с годными коробками проход молчит, ничего не обещает и не будит — счастливый путь. */
    @Test
    fun aQuietDayShowsNothing() = runTest {
        val quiet = Scenarios(database, Instant.parse("2027-03-01T05:00:00Z"))

        val report = quiet.dailyRound.run()
        val delivered = quiet.reminderOutbox.pass()

        assertEquals(DailyRound.Report(missed = 0, reminders = 0, raised = 0), report)
        assertEquals(emptyList<Any>(), quiet.notifier.shown)
        assertEquals(ReminderOutbox.Report(shown = 0, dismissed = 0, nextAt = null), delivered)
        assertNull(quiet.reminders.wakeAt)
    }
}
