package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.Reminder
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
import java.time.LocalTime
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
        // Обещание живёт у пункта: у каждого планового своё, у вчерашнего пропущенного — снято.
        val owed = nextMorning.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE))
            .filter { it.state == com.kert0n.medapp.domain.notification.Reminder.State.DUE }
            .map { Uuid.parse(it.key.subject) }.toSet()
        assertEquals(byDay.filterKeys { it != start }.values.map { it.id }.toSet(), owed)
        // В шторку сказан срок источника за день. Пропуск ждёт попапа пропущенного при входе, а не
        // шторки (C1 «Попап пропущенного»); приёмы ещё впереди, сводка — к своему часу.
        assertEquals(setOf(NotificationKind.EXPIRY_SOURCE_1D), nextMorning.notifier.shown.map { it.kind }.toSet())
        assertEquals(1, delivered.shown)
        assertEquals(
            listOf(NotificationKind.INTAKE_MISSED),
            nextMorning.reminderStore.awaiting(com.kert0n.medapp.domain.notification.NoticeDelivery.IN_APP_BANNER).map { it.kind }
        )
        assertEquals(1, nextMorning.reminderStore.ofKinds(listOf(NotificationKind.DAILY_DIGEST)).size)
        // Будильник один, и он на ближайший невыполненный срок — сегодняшний приём в 09:00 МСК.
        assertEquals(byDay.getValue(start.plusDays(1)).plannedAt, nextMorning.reminders.wakeAt)
        assertTrue(nextMorning.reminders.exact)

        // Повтор того же утра: пропусков больше нет, сказанное не повторяется.
        val again = nextMorning.dailyRound.run()
        assertEquals(0, again.missed)
        assertEquals(0, nextMorning.reminderOutbox.pass().shown)
        assertEquals(1, nextMorning.notifier.shown.size)
    }

    /** Без лечения и с годными коробками проход молчит, ничего не обещает и не будит — счастливый путь. */
    @Test
    fun aQuietDayShowsNothing() = runTest {
        val quiet = Scenarios(database, Instant.parse("2027-03-01T05:00:00Z"))

        val report = quiet.dailyRound.run()
        val delivered = quiet.reminderOutbox.pass()

        assertEquals(DailyRound.Report(missed = 0, promised = 0, withdrawn = 0), report)
        assertEquals(emptyList<Any>(), quiet.notifier.shown)
        assertEquals(ReminderOutbox.Report(shown = 0, dismissed = 0, blocked = 0, nextAt = null), delivered)
        assertNull(quiet.reminders.wakeAt)
    }

    /**
     * Человек перенёс сводку на вечер, пока утренняя ещё не сказана: обещание одно, и срок у него
     * новый — тем же проходом (PLAN D8). Красная проверка: обещать раньше, чем снимать, — снятое
     * воскресло бы только следующим проходом, и в этот день сводка вышла бы в прежний час.
     */
    @Test
    fun aMovedDigestGetsItsNewHourAtOnce() = runTest {
        treated(Scenarios(database, Instant.parse("2027-03-10T05:00:00Z")))
        val morning = Scenarios(database, Instant.parse("2027-03-11T02:00:00Z"))
        morning.dailyRound.run()
        assertEquals(Instant.parse("2027-03-11T09:00:00Z"), morning.reminderStore.ofKinds(listOf(NotificationKind.DAILY_DIGEST)).single().dueAt)

        morning.notificationSettings.settings = NotificationSettings(digestAt = LocalTime.of(18, 0))
        morning.dailyRound.run()

        val digest = morning.reminderStore.ofKinds(listOf(NotificationKind.DAILY_DIGEST)).single()
        assertEquals(Instant.parse("2027-03-11T18:00:00Z"), digest.dueAt)
        assertEquals(Reminder.State.DUE, digest.state)
    }

    /**
     * Сводка приходит не раньше своего часа (PLAN D8). Проход дня зовут и вход в приложение, и
     * загрузка устройства — в 05:00 обещание есть, но его срок ещё впереди; в 09:00 оно наступает.
     * Красная проверка: считать сроком сводки миг прохода — она вышла бы в пять утра, а в девять
     * её подавил бы собственный журнал.
     */
    @Test
    fun theDigestIsNotSaidBeforeItsHour() = runTest {
        treated(Scenarios(database, Instant.parse("2027-03-10T05:00:00Z")))
        val earlyMorning = Scenarios(database, Instant.parse("2027-03-11T02:00:00Z")) // 05:00 МСК

        earlyMorning.dailyRound.run()
        earlyMorning.reminderOutbox.pass()

        val digest = earlyMorning.reminderStore
            .ofKinds(listOf(NotificationKind.DAILY_DIGEST)).single()
        assertEquals(Instant.parse("2027-03-11T09:00:00Z"), digest.dueAt) // 09:00 в зоне устройства (UTC)
        assertEquals(0, earlyMorning.notifier.shown.count { it.kind == NotificationKind.DAILY_DIGEST })

        val atNine = Scenarios(database, Instant.parse("2027-03-11T09:00:00Z"), notifier = earlyMorning.notifier)
        atNine.reminderOutbox.pass()
        assertEquals(1, atNine.notifier.shown.count { it.kind == NotificationKind.DAILY_DIGEST })
    }
}
