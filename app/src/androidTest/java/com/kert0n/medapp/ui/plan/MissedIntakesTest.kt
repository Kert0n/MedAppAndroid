package com.kert0n.medapp.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.StoryClock
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.presentation.plan.MissedIntakesViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Попап пропущенного (PLAN C1 «Попап пропущенного»): что в нём стоит, что записывает «Принял» и что
 * значит крестик. Как он выглядит — `MissedIntakesPopupTest`; как делит вход с попапом срока —
 * `EntryPopupsTest`.
 *
 * Пропуски заводит **механизм**: лечение начато позавчера, проход дня отмечает неотвеченное
 * пропуском и обещает сказать о нём. Проверка обязательств руками не пишет.
 */
@RunWith(AndroidJUnit4::class)
class MissedIntakesTest {

    private lateinit var database: MedAppDatabase
    private val now: Instant = Instant.parse("2027-03-10T09:00:00Z")
    private val clock = StoryClock(now, ZoneOffset.UTC)
    private val shifts = TimeShifts()
    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        database.vocabulary().save(
            units = listOf(TABLETS).map { it.toStorageEntity() },
            forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
        )
        database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
        database.packageRepository().add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun scenarios(at: Instant = clock.now) = Scenarios(database, at)

    private fun model() = scenarios().let { scenarios ->
        MissedIntakesViewModel(
            outbox = scenarios.reminderOutbox,
            confirmation = scenarios.intakeConfirmation,
            today = Today(clock, shifts),
            reminders = scenarios.reminderStore,
            intakes = database.intakeRepository(),
            courses = database.courseRepository(),
            packages = database.packageRepository()
        ).also { opened += it }
    }

    /**
     * Лечение позавчера утром, в 08:00 по Москве: позавчерашний, вчерашний и сегодняшний пункты уже
     * наступили. Начато «тогда же», поэтому обязательства сказать о приёмах тоже заведены тогда.
     */
    private suspend fun startedTwoDaysAgo(): Map<LocalDate, CourseIntake> {
        val started = scenarios(now.minusSeconds(2 * 86_400 + 5 * 3_600))
        val created = started.courseDrafting.create("Нурофен")
        val saved = started.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 8), times = listOf(LocalTime.of(8, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved
        started.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return intakesOf(saved.draft.id)
    }

    private suspend fun intakesOf(courseId: Uuid): Map<LocalDate, CourseIntake> =
        database.intakeRepository().ofCourse(courseId).filterIsInstance<CourseIntake>().associateBy { it.slot.localDate }

    /**
     * **Неотвеченное прошлых дней — в попапе, сегодняшнее — нет.** Сегодняшний пункт стоит на
     * странице дня, а в прошлые дни плана не уйти: попап — единственное место ответить за них.
     */
    @Test
    fun unansweredPastDaysComeUpButTodayDoesNot(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        scenarios().dailyRound.run()
        val model = model()

        val shown = watching(model.state) { it.awaiting(PATIENTLY) { s -> s.rows.size == 2 } }

        assertEquals(
            setOf(intakes.getValue(LocalDate.of(2027, 3, 8)).id, intakes.getValue(LocalDate.of(2027, 3, 9)).id),
            shown.rows.mapNotNull { it.intakeId }.toSet()
        )
        assertEquals(setOf(LocalDate.of(2027, 3, 8), LocalDate.of(2027, 3, 9)), shown.rows.mapNotNull { it.on }.toSet())
    }

    /**
     * **Ответ человека попап не переспрашивает.** Вчера Светлана сама нажала «Пропустил» — это
     * решение, а не забытый приём, и в попапе ему не место.
     */
    @Test
    fun aDeclinedIntakeIsNotAskedAgain(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        val yesterday = intakes.getValue(LocalDate.of(2027, 3, 9))
        scenarios(yesterday.plannedAt.plusSeconds(60)).intakeDeclining.decline(yesterday.id, yesterday.plannedAt)
        scenarios().dailyRound.run()
        val model = model()

        val shown = watching(model.state) { it.awaiting(PATIENTLY) { s -> s.rows.isNotEmpty() } }

        assertEquals(listOf(intakes.getValue(LocalDate.of(2027, 3, 8)).id), shown.rows.mapNotNull { it.intakeId })
    }

    /**
     * **«Принял» записывает приём в момент пункта** и строка уходит: человек говорит, что выпил, как
     * было назначено, — а не что выпил сейчас, наутро.
     */
    @Test
    fun confirmingWritesAtTheIntakesMomentAndTheRowLeaves(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        scenarios().dailyRound.run()
        val yesterday = intakes.getValue(LocalDate.of(2027, 3, 9))
        val model = model()

        watching(model.state) { state ->
            val shown = state.awaiting(PATIENTLY) { it.rows.size == 2 }
            model.confirm(yesterday.id, shown.planned.getValue(yesterday.id))
            state.awaiting(PATIENTLY) { s -> s.rows.none { it.intakeId == yesterday.id } }
        }

        val taken = database.intakeRepository().find(yesterday.id) as CourseIntake
        assertEquals(IntakeStatus.TAKEN, taken.status)
        assertEquals(yesterday.plannedAt, taken.projection().answer?.at)
    }

    /**
     * **Крестик — окончательно пропуски.** Закрытый попап не возвращается ни в этом окне, ни в
     * новом, а пункты остаются пропущенными.
     */
    @Test
    fun theCrossLeavesThemMissedForGood(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        scenarios().dailyRound.run()
        val model = model()

        watching(model.state) { state ->
            model.dismiss(state.awaiting(PATIENTLY) { it.rows.size == 2 }.told)
            state.awaiting(PATIENTLY) { it.isEmpty }
        }
        val reopened = model()
        // Отметка пишется следом за крестиком: новое окно ждёт её, а не мгновения.
        com.kert0n.medapp.fixture.await("крестик отметил сказанное") {
            scenarios().reminderStore.awaiting(com.kert0n.medapp.domain.notification.NoticeDelivery.IN_APP_BANNER).isEmpty()
        }
        assertEquals(true, watching(reopened.state) { it.awaiting(PATIENTLY) { s -> s.isEmpty } }.isEmpty)
        assertEquals(IntakeStatus.MISSED, database.intakeRepository().find(intakes.getValue(LocalDate.of(2027, 3, 8)).id)?.status)
    }

    /**
     * **Закрытое вчера не закрывает завтрашнее.** Окно живёт до следующего дня; сегодняшний пункт
     * остаётся без ответа и назавтра приходит в тот же попап — закрытое помнится ключами, а не флагом.
     */
    @Test
    fun tomorrowsMissComesEvenAfterTheCross(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        scenarios().dailyRound.run()
        val today = intakes.getValue(LocalDate.of(2027, 3, 10))
        val model = model()

        watching(model.state) { state ->
            model.dismiss(state.awaiting(PATIENTLY) { it.rows.size == 2 }.told)
            state.awaiting(PATIENTLY) { it.isEmpty }

            clock.now = now.plusSeconds(86_400)
            scenarios().dailyRound.run()
            shifts.happened()
            val tomorrow = state.awaiting(PATIENTLY) { !it.isEmpty }
            assertEquals(listOf(today.id), tomorrow.rows.mapNotNull { it.intakeId })
        }
    }

    /**
     * **Крестик отмечает сказанным только то, что в попапе стояло** (CodeRabbit 4030083081). Лечение
     * назначено по Москве, а телефон живёт на час-три западнее: в 01:00 по Москве сегодняшний
     * пункт уже пропуск, но на телефоне ещё тот же день, и попап его не показывает. Крестик по
     * прочим строкам отметил сказанным и его — и назавтра последнего шанса у пункта не было.
     */
    @Test
    fun theCrossDoesNotTellWhatThePopupHid(): Unit = runBlocking {
        val intakes = startedTwoDaysAgo()
        // 22:00 по Гринвичу — 01:00 11 марта по Москве: пункт 10-го пропущен, а на телефоне ещё 10-е.
        clock.now = Instant.parse("2027-03-10T22:00:00Z")
        scenarios().dailyRound.run()
        val today = intakes.getValue(LocalDate.of(2027, 3, 10))
        val model = model()

        watching(model.state) { state ->
            model.dismiss(state.awaiting(PATIENTLY) { it.rows.size == 2 }.told)
            state.awaiting(PATIENTLY) { it.isEmpty }
            com.kert0n.medapp.fixture.await("крестик отметил сказанное") {
                scenarios().reminderStore.awaiting(com.kert0n.medapp.domain.notification.NoticeDelivery.IN_APP_BANNER)
                    .none { it.key.subject.startsWith(intakes.getValue(LocalDate.of(2027, 3, 9)).id.toString()) }
            }

            clock.now = Instant.parse("2027-03-11T09:00:00Z")
            shifts.happened()
            val tomorrow = state.awaiting(PATIENTLY) { !it.isEmpty }
            assertEquals(listOf(today.id), tomorrow.rows.mapNotNull { it.intakeId })
        }
    }

    /**
     * **Из выброшенной коробки быстрого ответа нет.** Коробку выбросили, пока попап ждал: «Принял»
     * из неё ничего бы не записал, кроме отказа поверх попапа (снимок BigLatest). Строка остаётся —
     * пропуск по-прежнему не отвечен, — но кнопки у неё нет.
     */
    @Test
    fun aRowWhoseBoxWasThrownAwayOffersNoQuickAnswer(): Unit = runBlocking {
        startedTwoDaysAgo()
        scenarios().dailyRound.run()
        val model = model()

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { s -> s.rows.size == 2 && s.rows.all { it.canConfirm } }
            scenarios().packageRemoval.remove(PACK)
            state.awaiting(PATIENTLY) { s -> s.rows.size == 2 && s.rows.none { it.canConfirm } }
        }
    }

    private companion object {
        val PATIENTLY = 15.seconds
    }
}
