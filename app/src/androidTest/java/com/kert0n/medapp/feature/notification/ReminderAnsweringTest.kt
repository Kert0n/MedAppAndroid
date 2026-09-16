package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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

    private suspend fun treated(fromPackage: Uuid = PACK): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(fromPackage, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun first(id: Uuid): CourseIntake =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }

    private fun reminderKey(intake: CourseIntake) = NotificationKey.intake(intake.id, NotificationKind.INTAKE_DUE)

    private fun reminderFor(intake: CourseIntake) =
        Reminder(reminderKey(intake), com.kert0n.medapp.domain.notification.NotificationTarget.Intake(intake.id), intake.plannedAt)

    /**
     * **Все три действия — без экрана** (C1, поправка владельца 2026-09-16): приложение из шторки
     * не открывается. У остальных видов действий нет вовсе: отвечать там не на что.
     */
    @Test
    fun onlyTheIntakeReminderCarriesActions() {
        assertEquals(
            listOf(NotificationAction.TAKE, NotificationAction.SKIP, NotificationAction.SNOOZE),
            NotificationKind.INTAKE_DUE.actions
        )
        assertTrue(NotificationKind.entries.filter { it != NotificationKind.INTAKE_DUE }.all { it.actions.isEmpty() })
    }

    /**
     * **«Принял» пишет в фоне** (C1): плановая пачка и доза, в момент нажатия, без перехода в
     * приложение. Открывай его — и человек, нажавший кнопку у плиты, видит мелькнувшее окно и
     * «Аптечки», будто ничего не произошло (опыт владельца на Pixel).
     */
    @Test
    fun takeWritesWithoutTheScreen() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))

        assertEquals(ReminderAnswering.Response.Done, scenarios.reminderAnswering.take(intake.id))

        assertEquals(IntakeStatus.TAKEN, requireNotNull(database.intakeRepository().find(intake.id)).status)
        assertEquals(tablets("18"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertEquals(Reminder.State.WITHDRAWN, requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).state)
    }

    /**
     * **Не записалось — «нужно ваше решение»**, а не тишина (C1). Коробку, из которой лечение
     * берёт, убрали из аптечки: «Принял» ничего не пишет и заводит обязательство, нажатие на
     * которое ведёт на карточку пункта. Промолчи — и человек уверен, что приём записан.
     */
    @Test
    fun aTakeThatCannotBeWrittenAsksForADecision() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.packageRemoval.remove(PACK)

        assertEquals(ReminderAnswering.Response.NeedsDecision, scenarios.reminderAnswering.take(intake.id))

        assertEquals(IntakeStatus.PLANNED, requireNotNull(database.intakeRepository().find(intake.id)).status)
        val decision = requireNotNull(scenarios.reminderStore.find(NotificationKey.intake(intake.id, NotificationKind.INTAKE_DECISION)))
        assertEquals(Reminder.State.DUE, decision.state)
        assertEquals(com.kert0n.medapp.domain.notification.NotificationTarget.Intake(intake.id), decision.target)
    }

    /**
     * **«Принял» из шторки — одной транзакцией** (PLAN F5, CodeRabbit 4030083049). Коробку убрали,
     * и «Принял» не записался; пока он заводил «нужно ваше решение», Светлана на карточке нажала
     * «Пропустил». Отказ снял обязательства, какие были, — а решение легло после него и висит в
     * шторке над уже отвеченным пунктом. Прочитанное «не записалось» верно только в той транзакции,
     * где его прочли.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun aRefusalWhileTakeAsksForADecisionLeavesNoDecisionBehind(): Unit = runBlocking {
        val id = treated()
        val intake = first(id)
        scenarios.packageRemoval.remove(PACK)
        val real = database.transactions()
        val refusal = CompletableDeferred<kotlinx.coroutines.Deferred<*>>()
        // Отказ приходит ровно перед тем, как «Принял» заводит решение: чужой транзакцией, со своего экрана.
        val refusingBeforePromise = object : com.kert0n.medapp.queue.Transactions {
            override suspend fun <T> run(block: suspend () -> T): T {
                if (!refusal.isCompleted) {
                    val declined = GlobalScope.async(Dispatchers.IO) { scenarios.intakeDeclining.decline(intake.id, now) }
                    refusal.complete(declined)
                    withTimeoutOrNull(1_000) { declined.await() }
                }
                return real.run(block)
            }
        }
        val answering = ReminderAnswering(
            scenarios.intakeDeclining, scenarios.intakeConfirmation, database.intakeRepository(),
            ReminderPromising(scenarios.reminderStore, scenarios.notificationSettings, refusingBeforePromise),
            scenarios.reminderStore, scenarios.reminderWithdrawal, scenarios.notificationSettings, real,
            Clock.fixed(now, ZoneOffset.UTC)
        )

        answering.take(intake.id)
        refusal.await().await()

        assertEquals(IntakeStatus.MISSED, requireNotNull(database.intakeRepository().find(intake.id)).status)
        val decision = scenarios.reminderStore.find(NotificationKey.intake(intake.id, NotificationKind.INTAKE_DECISION))
        assertTrue("решение висит над отвеченным пунктом: $decision", decision == null || decision.state == Reminder.State.WITHDRAWN)
    }

    /** Решение принято на карточке — «нужно ваше решение» снимается вместе с напоминанием. */
    @Test
    fun answeringTheIntakeWithdrawsTheDecisionNotice() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(
            listOf(Reminder(NotificationKey.intake(intake.id, NotificationKind.INTAKE_DECISION), com.kert0n.medapp.domain.notification.NotificationTarget.Intake(intake.id), now))
        )

        scenarios.intakeDeclining.decline(intake.id, now)

        assertEquals(
            Reminder.State.WITHDRAWN,
            requireNotNull(scenarios.reminderStore.find(NotificationKey.intake(intake.id, NotificationKind.INTAKE_DECISION))).state
        )
    }

    /** «Отложить» сдвигает будильник на snoozeMinutes; plannedAt и статус пункта прежние. */
    @Test
    fun snoozeMovesOnlyTheAlarm() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))

        val response = scenarios.reminderAnswering.snooze(intake.id) as ReminderAnswering.Response.Snoozed

        assertEquals(now.plusSeconds(15 * 60), response.at)
        // Сдвиг лежит в обязательстве, а не в системе: он переживёт и проход дня, и перезагрузку.
        assertEquals(response.at, requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).dueAt)
        val same = first(id)
        assertEquals(intake.plannedAt, same.plannedAt)
        assertEquals(IntakeStatus.PLANNED, same.status)
    }

    /** «Пропустить» — отказ человека: пункт пропущен, обязательство отозвано, карточка погашена. */
    @Test
    fun skipDeclinesAndWithdraws() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))

        assertEquals(ReminderAnswering.Response.Done, scenarios.reminderAnswering.skip(intake.id))

        assertEquals(IntakeStatus.MISSED, requireNotNull(database.intakeRepository().find(intake.id)).status)
        assertEquals(Reminder.State.WITHDRAWN, requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).state)
        scenarios.reminderOutbox.pass()
        assertTrue(scenarios.notifier.dismissed.contains(reminderKey(intake)))
    }

    /** Отмена курса снимает обязательства всех его пунктов и гасит показанное (PLAN D8). */
    @Test
    fun cancellingTheCourseWithdrawsEveryReminder() = runTest {
        val id = treated()
        val planned = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>()
        scenarios.reminderPromising.promise(planned.map { reminderFor(it) })

        scenarios.courseCancellation.cancel(id)
        scenarios.reminderOutbox.pass()

        assertEquals(emptyList<Reminder>(), scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)))
        assertEquals(planned.map { reminderKey(it) }.toSet(), scenarios.notifier.dismissed.toSet())
        assertNull(scenarios.reminders.wakeAt)
    }

    /**
     * Показанное гасится **после** фиксации: транзакция, откатившаяся после закрытия курса,
     * оставляет пункты плановыми — и их обязательства целы (красная проверка: гасить внутри
     * транзакции — карточки пропали бы у неотменённых пунктов). Теперь это держит сама форма:
     * отзыв — строка в той же базе, и откат уносит его вместе с остальным.
     */
    @Test
    fun aRolledBackCancellationLeavesTheAlarmsInPlace() = runTest {
        val id = treated()
        val planned = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>()
        scenarios.reminderPromising.promise(planned.map { reminderFor(it) })
        val real = database.transactions()
        val failingAfterWork = object : com.kert0n.medapp.queue.Transactions {
            override suspend fun <T> run(block: suspend () -> T): T = real.run<T> {
                block()
                throw IllegalStateException("сбой фиксации")
            }
        }
        val cancellation = com.kert0n.medapp.feature.course.CourseCancellation(
            database.courseRepository(), database.intakeRepository(), scenarios.courseCalendar, scenarios.courseClosing,
            failingAfterWork, Clock.fixed(now, ZoneOffset.UTC)
        )

        val failure = runCatching { cancellation.cancel(id) }.exceptionOrNull()

        assertEquals("сбой фиксации", failure?.message)
        assertTrue(requireNotNull(database.courseRepository().findRecord(id)).isOpen)
        assertEquals(planned.map { reminderKey(it) }.toSet(), scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { it.key }.toSet())
        assertTrue(scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).all { it.state == Reminder.State.DUE })
        scenarios.reminderOutbox.pass()
        assertEquals(emptyList<NotificationKey>(), scenarios.notifier.dismissed)
    }

    /**
     * Прежде «Отложить» жило только в `AlarmManager`, и ближайший проход дня переставлял будильник
     * обратно на `plannedAt` — момент в прошлом, — отчего напоминание возвращалось через минуты
     * вместо пятнадцати. Теперь срок лежит в обязательстве.
     *
     * **Красная проверка** — заведение обязательства с плановым сроком поверх отложенного: сделай
     * `raiseAll` перезаписью вместо «завести недостающее», и отсрочка исчезнет. Проход дня здесь
     * же, потому что зовёт он ровно это.
     */
    @Test
    fun aDeferredReminderSurvivesTheDailyRound() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))
        val snoozed = (scenarios.reminderAnswering.snooze(intake.id) as ReminderAnswering.Response.Snoozed).at

        // Календарь обещает тем же ключом и плановым сроком — уже обещанного это не трогает.
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))
        scenarios.dailyRound.run()
        scenarios.reminderOutbox.pass()

        assertEquals(snoozed, requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).dueAt)
        assertEquals(Reminder.State.DUE, requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).state)
        // Будильник — на отложенный момент, а не на плановый: система исполняет сохранённый срок.
        assertEquals(snoozed, scenarios.reminders.wakeAt)
        assertEquals(emptyList<Any>(), scenarios.notifier.shown.filter { it.kind == NotificationKind.INTAKE_DUE })
    }

    /**
     * Два препарата в один миг — две карточки и **один** будильник: число записей в системе от
     * числа обязательств не зависит (решение владельца 2026-09-14). Прежде на этот миг стояло два
     * будильника, и различал их только `hashCode` ключа.
     */
    @Test
    fun twoIntakesAtTheSameMomentGiveTwoCardsAndOneAlarm() = runTest {
        // Активное назначение пачки уникально, поэтому у второго лечения своя коробка.
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("20"), form = TABLET_FORM))
        val both = listOf(treated(), treated(OTHER_PACK)).map { first(it) }
        assertEquals(1, both.map { it.plannedAt }.toSet().size) // оба пункта стоят на один миг
        scenarios.reminderPromising.promise(both.map { reminderFor(it) })

        val delivered = scenarios.reminderOutbox.pass()

        assertEquals(2, delivered.shown)
        assertEquals(both.map { reminderKey(it) }.toSet(), scenarios.notifier.shown.map { it.key }.toSet())
        assertEquals(1, scenarios.reminders.settings.size)
    }

    /**
     * Система забыла — приложение помнит: новый владелец доставки над той же базой поднимает
     * будильник из обязательств, ничего не зная о прежнем. Так расписание переживает перезагрузку
     * и перезапуск процесса (PLAN D8).
     */
    @Test
    fun aFreshOutboxRearmsFromTheTable() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))

        // Процесс поднялся заново: будильников в системе нет, таблица на месте.
        val afterRestart = Scenarios(database, now.minusSeconds(3600))
        assertNull(afterRestart.reminders.wakeAt)
        afterRestart.reminderOutbox.pass()

        assertEquals(intake.plannedAt, afterRestart.reminders.wakeAt)
    }

    /**
     * «Отложить» по обязательству, которого уже сняли (курс отменили, пока карточка висела):
     * воскрешать снятое нельзя, а человеку надо ответить так, чтобы карточка ушла. Сейчас переход
     * бросает, приёмник ловит исключение **до** гашения — и карточка остаётся висеть.
     */
    @Test
    fun snoozingAWithdrawnReminderIsDoneAndDoesNotReviveIt() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderPromising.promise(listOf(reminderFor(intake)))
        scenarios.reminderWithdrawal.withdrawKeys(listOf(reminderKey(intake)))

        val response = runCatching { scenarios.reminderAnswering.snooze(intake.id) }

        assertEquals(ReminderAnswering.Response.Done, response.getOrNull())
        assertEquals(
            Reminder.State.WITHDRAWN,
            requireNotNull(scenarios.reminderStore.find(reminderKey(intake))).state
        )
    }

    /** «Отложить» по обязательству, которого нет вовсе: отвечать сроком, которого не записали, — ложь. */
    @Test
    fun snoozingAMissingReminderIsDoneNotSnoozed() = runTest {
        val id = treated()
        val intake = first(id)
        scenarios.reminderWithdrawal.withdrawKeys(listOf(reminderKey(intake)))
        scenarios.reminderOutbox.pass()
        assertNull(scenarios.reminderStore.find(reminderKey(intake)))

        val response = scenarios.reminderAnswering.snooze(intake.id)

        assertEquals("срок назван, а записать его было некуда", ReminderAnswering.Response.Done, response)
    }
}
