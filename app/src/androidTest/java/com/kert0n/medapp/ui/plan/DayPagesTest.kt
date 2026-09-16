package com.kert0n.medapp.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.AllAllowed
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPlanViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.Readiness
import com.kert0n.medapp.presentation.plan.DayPermissionsPresentationDTO
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.StoryClock
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Страницы дня (PLAN H3 №12). Какой день спрашивают и когда спрашивают заново, проверяет
 * `DayPlanningTest`; здесь — что страница у каждого сдвига **своя** и что второй взгляд на ту же
 * страницу нового чтения не заводит.
 */
@RunWith(AndroidJUnit4::class)
class DayPagesTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(
        readiness: NotificationReadiness = AllAllowed,
        clock: Clock = this.clock,
        shifts: ClockShifts = QuietClock
    ) = DayPlanViewModel(
        today = Today(clock, shifts),
        planning = DayPlanning(Today(clock, shifts), database.reportRepository()),
        confirmation = scenarios.intakeConfirmation,
        declining = scenarios.intakeDeclining,
        devicePermissions = AllAllowed,
        readiness = readiness,
        clock = clock,
        reminders = scenarios.reminderStore,
        intakes = database.intakeRepository(),
        courses = database.courseRepository()
    ).also { opened += it }

    private fun shown(model: DayPlanViewModel, daysAhead: Int): DayPagePresentationDTO {
        val page = model.page(daysAhead)
        val state = watching(page) { it.awaiting(PATIENTLY) { s -> s is ScreenState.Ready } }
        return (state as ScreenState.Ready).value
    }

    /**
     * У каждого сдвига своя страница: человек, листающий вперёд, держит на виду две разом, и одно
     * состояние на всех показало бы соседней странице чужие строки.
     */
    @Test
    fun eachOffsetHasItsOwnPage() {
        val model = model()

        assertEquals(LocalDate.of(2027, 3, 10), shown(model, 0).date)
        assertEquals(LocalDate.of(2027, 3, 11), shown(model, 1).date)
        assertEquals(0, shown(model, 0).daysAhead)
        assertEquals(1, shown(model, 1).daysAhead)
    }

    /**
     * Вернувшийся на ту же страницу получает её же. Спрашивай чтение заново на каждый оборот
     * вёрстки — и листание заводило бы по подписке на кадр, а страница мигала бы ожиданием.
     */
    @Test
    fun theSamePageIsNotAskedTwice() {
        val model = model()

        assertSame(model.page(0), model.page(0))
    }

    /**
     * **Заглушённый канал называется.** Приложению разрешено, а канал «Приёмы» человек заглушил
     * долгим нажатием на карточку: показ отвечает «нельзя», полка растёт. Спроси «День» другой
     * ответ, чем показ, — и он скажет, что всё в порядке, а человек так и не узнает, почему
     * молчит телефон (разбор U5).
     */
    @Test
    fun aMutedIntakeChannelIsNamedByTheDay() {
        val muted = object : NotificationReadiness {
            override fun now() = Readiness(allowed = true, muted = setOf(NotificationChannel.INTAKES))
        }

        assertEquals(DayPermissionsPresentationDTO(intakesMuted = true), model(muted).permissions.value)
    }

    /** Заглушён чужой канал — о приёмах сказать есть чем, и «День» молчит. */
    @Test
    fun aMutedChannelOfAnotherKindIsNotTheDaysBusiness() {
        val muted = object : NotificationReadiness {
            override fun now() = Readiness(allowed = true, muted = setOf(NotificationChannel.EXPIRY))
        }

        assertEquals(DayPermissionsPresentationDTO(), model(muted).permissions.value)
    }

    /**
     * **На полке — только прошлые дни** (PLAN C1 «Полка»). Лечение идёт со вчера, сказать было
     * нечем: вчерашний пункт ждёт на полке, а сегодняшний и так стоит в самом дне с кнопками.
     * Положи его ещё и на полку — и человек видит одну дозу дважды и не понимает, одна она или две
     * (разбор U5, `BigLatest`).
     */
    @Test
    fun onlyPastDaysStandOnTheShelf(): Unit = runBlocking {
        val (yesterday, today) = startedYesterdayMorning()
        val model = model()

        watching(model.page(0)) { page ->
            val ready = page.awaiting(PATIENTLY) { state ->
                state is ScreenState.Ready && state.value.unannounced.map { it.intakeId }.toSet() == setOf(yesterday)
            }
            assertEquals(listOf(today), (ready as ScreenState.Ready).value.items.mapNotNull { it.intakeId })
        }
    }

    /**
     * **Полка меняется сменой дня, а не записью в таблицу** (разбор #51). Страница открыта через
     * полночь: сегодняшний неотвеченный пункт становится вчерашним и уходит на полку сам, хотя
     * обязательства за это время не менялись. Спроси полку часами только при записи в таблицу — и
     * она стоит прежней, пока что-нибудь постороннее не запишется.
     */
    @Test
    fun theShelfFollowsTheDayWithoutAWriteToTheTable(): Unit = runBlocking {
        val (yesterday, today) = startedYesterdayMorning()
        val moving = StoryClock(now, ZoneOffset.UTC)
        val shifts = TimeShifts()
        val model = model(clock = moving, shifts = shifts)

        watching(model.page(0)) { page ->
            page.awaiting(PATIENTLY) { state ->
                state is ScreenState.Ready && state.value.unannounced.map { it.intakeId }.toSet() == setOf(yesterday)
            }
            moving.now = now.plus(java.time.Duration.ofDays(1))
            shifts.happened()
            page.awaiting(PATIENTLY) { state ->
                state is ScreenState.Ready && state.value.date == LocalDate.of(2027, 3, 11) &&
                    state.value.unannounced.map { it.intakeId }.toSet() == setOf(yesterday, today)
            }
        }
    }

    /**
     * Лечение со вчерашнего утра, в 08:00 по Москве: вчерашний и сегодняшний пункты наступили, ответа
     * нет. Обязательства заводит сам календарь при начале лечения — проверка их руками не пишет.
     */
    private suspend fun startedYesterdayMorning(): Pair<kotlin.uuid.Uuid, kotlin.uuid.Uuid> {
        database.vocabulary().save(
            units = listOf(TABLETS).map { it.toStorageEntity() },
            forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
        )
        database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
        database.packageRepository().add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 9), times = listOf(LocalTime.of(8, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(3)),
                CourseDrafting.Edit.Attach(PACK, Doses(3))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        // Календарь держит окно вперёд, и завтрашний пункт тоже есть: история о вчера и сегодня.
        val byDay = database.intakeRepository().ofCourse(saved.draft.id)
            .filterIsInstance<com.kert0n.medapp.domain.intake.CourseIntake>()
            .associateBy { it.slot.localDate }
        val yesterday = checkNotNull(byDay[LocalDate.of(2027, 3, 9)]) { "завязка: нет вчерашнего пункта среди ${byDay.keys}" }
        val today = checkNotNull(byDay[LocalDate.of(2027, 3, 10)]) { "завязка: нет сегодняшнего пункта среди ${byDay.keys}" }
        return yesterday.id to today.id
    }

    private companion object {
        /** Столько ждём чтения: между подпиской и состоянием стоят потоки Room. */
        val PATIENTLY: Duration = 15.seconds
    }
}
