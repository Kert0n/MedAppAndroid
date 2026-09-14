package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.Mechanisms
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Протокол согласования (PLAN D8, C1 B20): основание читается той же транзакцией, что пишет
 * обязательство; запись применяется к тому, что лежит сейчас; показ по устаревшему основанию
 * гасится. Проверяется **через механизм** — запущенные владельцы и сигнал после коммита, — а не
 * ручными `pass()` и `reconcile()`: транзакция защищает запись, а решение до неё и системный
 * показ после неё защищает только протокол. Каждая проверка названа поведением, которое она
 * останавливает; все шесть воспроизводили дефекты разбора GPT среза #30 и были красными до фикса.
 */
@RunWith(AndroidJUnit4::class)
class NotificationProtocolTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private lateinit var mechanisms: Mechanisms
    private val now: Instant = Instant.parse("2027-03-10T05:00:00Z") // 08:00 МСК 10 марта

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        mechanisms = Mechanisms(scenarios, now)
    }

    @After
    fun tearDown() {
        mechanisms.close()
        database.close()
    }

    private fun intake(at: Instant = now, id: Uuid = Uuid.random()) =
        Reminder(NotificationKey.intake(id, NotificationKind.INTAKE_DUE), NotificationTarget.Intake(id), at)

    /** Лечение из `PACK`, пять доз с сегодняшнего утра. */
    private suspend fun treated(): Uuid {
        database.packageRepository().add(pack(form = TABLET_FORM, quantity = tablets("20")))
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10), times = listOf(LocalTime.of(9, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    private suspend fun firstIntake(course: Uuid): CourseIntake =
        database.intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>().minBy { it.plannedAt }

    /**
     * **Точный срок не ждёт неточного будильника.** Сводка обещана на 09:00 — просьба «примерно»,
     * приём на 09:01 — «ровно». Одна постановка на ближайший срок брала точность от него же:
     * неточная на 09:00, а точной на 09:01 не было вовсе — она появилась бы только после того, как
     * Android соизволит исполнить неточную, а это бывает и через двадцать минут. Ставим обе, заранее.
     */
    @Test
    fun anExactIntakeIsWokenForItselfNotAfterTheEarlierInexactWake() = runBlocking {
        val day = LocalDate.of(2027, 3, 10)
        val digest = Reminder(NotificationKey.digest(day), NotificationTarget.DayPlan(day), now.plusSeconds(3_600))
        val exact = intake(now.plusSeconds(3_660))

        scenarios.reminderStore.saveAll(listOf(digest, exact))

        mechanisms.await("постановки на оба срока") {
            scenarios.reminders.exactAt == exact.dueAt && scenarios.reminders.inexactAt == digest.dueAt
        }
    }

    /**
     * **Сверка, прочитавшая пункт до ответа человека, не воскрешает его напоминание.** Сверка
     * читала плановые пункты, человек в это время подтвердил первый — напоминание снято, — а сверка
     * записала обязательство из старого списка: `TAKEN` у приёма и `DUE` у напоминания разом.
     * Ответ уходит в другую транзакцию и другой поток: пока сверка держит свою, он ждёт замка, и
     * ничего между её чтением и записью не вклинивается; закончив, он снимает напоминание сам.
     */
    @Test
    fun aReconciliationThatReadBeforeAnAnswerDoesNotRevive() = runBlocking {
        val course = treated()
        val first = firstIntake(course)
        val real = database.intakeRepository()
        val answering = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var answer: kotlinx.coroutines.Job? = null
        val readBeforeTheAnswer = object : IntakeStorageRepository by real {
            override suspend fun plannedBefore(until: Instant): List<CourseIntake> {
                val before = real.plannedBefore(until)
                if (answer == null && until == Instant.ofEpochMilli(Long.MAX_VALUE)) {
                    // Ответ человека — в своём потоке; сверке даётся секунда, чтобы он успел,
                    // если ей нечем его задержать.
                    val job = answering.launch { scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed() }
                    answer = job
                    withTimeoutOrNull(1_000) { job.join() }
                }
                return before
            }
        }
        try {
            NotificationReconciliation(
                readBeforeTheAnswer, database.packageRepository(), database.courseRepository(), scenarios.reminderStore,
                database.queueRepository(), scenarios.reminderPromising, scenarios.reminderWithdrawal,
                scenarios.notificationSettings, scenarios.transactions
            ).reconcile(now, ZoneOffset.UTC)
            requireNotNull(answer).join()
            mechanisms.settle()

            assertEquals(IntakeStatus.TAKEN, (real.find(first.id) as CourseIntake).status)
            val reminder = scenarios.reminderStore.find(NotificationKey.intake(first.id, NotificationKind.INTAKE_DUE))
            assertFalse("напоминание о принятом пункте снова обещано", reminder?.state == Reminder.State.DUE)
        } finally {
            answering.cancel()
        }
    }

    /**
     * **Уборка не удаляет воскрешённое.** Владелец доставки прочитал отозванное, погасил в системе
     * и удалил по ключам — а между чтением и удалением повод вернулся, и `promise` воскресил
     * обязательство. Удаляется только то, что всё ещё отозвано, перечитанное в той же транзакции,
     * что удаляет.
     */
    @Test
    fun cleanupSparesAnObligationRevivedMeanwhile() = runBlocking {
        val reminder = intake(now.plusSeconds(600))
        var revived = false
        scenarios.notifier.onDismiss = { key ->
            if (key == reminder.key) {
                scenarios.reminderPromising.promise(listOf(intake(reminder.dueAt, (reminder.target as NotificationTarget.Intake).intakeId)))
                revived = true
            }
        }
        scenarios.reminderStore.saveAll(listOf(reminder))
        scenarios.reminderWithdrawal.withdrawKeys(listOf(reminder.key))

        mechanisms.await("гашение отозванного") { revived }
        mechanisms.settle()

        val left = scenarios.reminderStore.find(reminder.key)
        assertNotNull("воскрешённое обязательство удалено уборкой", left)
        assertEquals(Reminder.State.DUE, left!!.state)
    }

    /**
     * **Отложенное во время ожидания сети не показывается — или гасится тем же проходом.** Пока
     * владелец доставки ждал свежесть, человек нажал «Отложить»: отсрочка в базе цела, но карточка
     * показана по объекту, прочитанному до ожидания, и висит до нового срока — следующий проход
     * снимает только отозванное. Наступившее перечитывается после ожидания, а показ, разошедшийся
     * с обязательством, гасится.
     */
    @Test
    fun aSnoozeDuringTheRefreshIsNotShownOrIsDismissed() = runBlocking {
        val reminder = intake()
        val intakeId = (reminder.target as NotificationTarget.Intake).intakeId
        scenarios.freshness.meanwhile = { scenarios.reminderAnswering.snooze(intakeId) }

        scenarios.reminderStore.saveAll(listOf(reminder))
        mechanisms.await("проход после записи") { scenarios.freshness.asked >= 1 }
        mechanisms.settle()

        assertTrue(
            "отложенное показано и висит: shown=${scenarios.notifier.shown.map { it.key }}, dismissed=${scenarios.notifier.dismissed}",
            scenarios.notifier.shown.none { it.key == reminder.key } || reminder.key in scenarios.notifier.dismissed
        )
        val deferred = requireNotNull(scenarios.reminderStore.find(reminder.key))
        assertEquals(Reminder.State.DUE, deferred.state)
        assertTrue("отсрочка потеряна", deferred.dueAt.isAfter(now))
    }

    /**
     * **Исправленный срок годности гасит предупреждение без прохода дня.** Коробка-источник кончалась
     * через три дня — сказано; человек исправил дату на десять дней вперёд. Прежде предупреждение
     * висело до следующего `DailyRound`: изменение коробки сверку не звало. Теперь основания сами
     * зовут сверку, и владелец доставки гасит снятое.
     */
    @Test
    fun correctingAnExpiryWithdrawsItsWarningWithoutADailyRound() = runBlocking {
        treated()
        val soon = ExpiryDate(LocalDate.of(2027, 3, 13))
        scenarios.packageDescribing.describe(PACK, database.packageRepository().find(PACK)!!.facts.copy(expiresOn = soon))
        scenarios.dailyRound.run()
        val key = NotificationKey.expiry(PACK, soon, NotificationKind.EXPIRY_SOURCE_3D)
        mechanisms.await("предупреждение за три дня показано") { scenarios.notifier.shown.any { it.key == key } }

        val later = ExpiryDate(LocalDate.of(2027, 3, 20))
        scenarios.packageDescribing.describe(PACK, database.packageRepository().find(PACK)!!.facts.copy(expiresOn = later))

        mechanisms.await("прежнее предупреждение погашено без прохода дня") { key in scenarios.notifier.dismissed }
        assertTrue(scenarios.notifier.shown.none { it.key == NotificationKey.expiry(PACK, later, NotificationKind.EXPIRY_SOURCE_3D) })
    }

    /**
     * **Наступившее за время ожидания свежести показывается тем же проходом.** «Сейчас» берётся
     * после ожидания: пока ждали сеть, срок пункта наступил, и он должен уйти в шторку сейчас, а не
     * ждать следующего повода — иначе первый проход процесса, у которого будильника ещё нет,
     * оставил бы его висеть.
     */
    @Test
    fun whatBecomesDueDuringTheRefreshIsShownInTheSamePass() = runBlocking {
        val clock = com.kert0n.medapp.fixture.TickingClock(now)
        val outbox = ReminderOutbox(
            scenarios.reminderStore, scenarios.notifier, scenarios.reminders, scenarios.freshness, scenarios.transactions,
            clock, CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
        val ripe = intake(now)                       // уже наступил — ради него и ждут свежесть
        val ripening = intake(now.plusSeconds(2))    // наступит, пока ждут
        scenarios.reminderStore.saveAll(listOf(ripe, ripening))
        scenarios.freshness.meanwhile = { clock.now = now.plusSeconds(3) }

        outbox.pass()

        assertTrue("наступившее за время ожидания не показано", scenarios.notifier.shown.any { it.key == ripening.key })
    }

    /**
     * **Сбой сверки повторяется сам.** Сверка живёт от сигнала оснований; если она упала, а
     * оснований больше никто не трогает, обещанное осталось бы несверенным до прохода дня. Цикл
     * владельца записывает сбой и назначает срок повтора.
     */
    @Test
    fun aFailedSweepSchedulesItsOwnRetry() = runBlocking {
        val failing = object : com.kert0n.medapp.domain.notification.NotificationSettingsSource {
            var failures = 0
            override suspend fun current(): com.kert0n.medapp.domain.notification.NotificationSettings {
                failures++
                error("настройки не прочитались")
            }
        }
        val reconciliation = NotificationReconciliation(
            database.intakeRepository(), database.packageRepository(), database.courseRepository(), scenarios.reminderStore,
            database.queueRepository(), scenarios.reminderPromising, scenarios.reminderWithdrawal, failing, scenarios.transactions
        )
        val upkeep = NotificationUpkeep(scenarios.reminderStore, reconciliation, java.time.Clock.fixed(now, ZoneOffset.UTC), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        upkeep.start()
        mechanisms.await("наблюдатель оснований встал") { upkeep.ready.value }

        database.packageRepository().add(pack(form = TABLET_FORM, quantity = tablets("20")))

        mechanisms.await("сверка сорвалась") { upkeep.state.value.lastFailure != null }
        assertEquals("повтор после сбоя не назначен", now.plus(NotificationUpkeep.RETRY_AFTER_FAILURE), upkeep.state.value.nextRunAt)
    }

    /**
     * **Повторная сверка молчит.** Сверку зовёт сигнал таблиц-оснований; если бы сверка без
     * изменений что-то писала, сигнал `reminders` будил бы владельца доставки, а тот — никого, но
     * каждая укладка снимка давала бы лишний проход. Вторая сверка подряд не пишет ни строки.
     */
    @Test
    fun aRepeatedReconciliationWritesNothing() = runBlocking {
        val writes = mutableListOf<String>()
        val watched = inMemoryDatabase { sql -> if (Regex("^(INSERT|UPDATE|DELETE)", RegexOption.IGNORE_CASE).containsMatchIn(sql.trim()) && sql.contains("reminders")) writes += sql }
        try {
            val own = Scenarios(watched, now)
            watched.packageRepository().add(pack(form = TABLET_FORM, quantity = tablets("20"), expiresOn = ExpiryDate(LocalDate.of(2027, 3, 13))))
            val created = own.courseDrafting.create("Ибупрофен")
            val saved = own.courseDrafting.edit(
                created.id, created.revision,
                listOf(
                    CourseDrafting.Edit.SetDose(dose("2")), CourseDrafting.Edit.SetForm(TABLET_FORM),
                    CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                    CourseDrafting.Edit.SetTotalDoses(Doses(5)), CourseDrafting.Edit.Attach(PACK, Doses(5))
                )
            ) as CourseDrafting.Outcome.Saved
            own.courseActivation.activate(saved.draft.id, saved.draft.revision)
            own.dailyRound.run()
            // Срок исправлен: прежнее предупреждение отзывается первой сверкой и лежит отозванным.
            own.packageDescribing.describe(PACK, watched.packageRepository().find(PACK)!!.facts.copy(expiresOn = ExpiryDate(LocalDate.of(2027, 3, 20))))
            own.notificationReconciliation.reconcile(now, ZoneOffset.UTC)
            writes.clear()

            own.notificationReconciliation.reconcile(now, ZoneOffset.UTC)

            assertEquals("сверка без изменений пишет: $writes", emptyList<String>(), writes)
        } finally {
            watched.close()
        }
    }
}
