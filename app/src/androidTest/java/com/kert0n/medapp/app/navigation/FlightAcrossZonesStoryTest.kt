package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.intakeOn
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.treatmentStarted
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Руслана — вахта через четыре часовых пояса** (`docs/истории.md`, U5).
 *
 * Android 14 без точных будильников, отсрочка на посадке, перезагрузка в самолёте и Новосибирск.
 * Лечение назначено по Москве, телефон переезжает на +4: время и зону двигает рассказ ([StoryWorld]).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FlightAcrossZonesStoryTest {

    private val thursday: LocalDate = LocalDate.of(2027, 4, 1)
    private val friday: LocalDate = thursday.plusDays(1)
    private val novosibirsk: ZoneId = ZoneId.of("Asia/Novosibirsk")

    private val world = StoryWorld.begin(moscow(thursday, 7, 0), MOSCOW)

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var database: MedAppDatabase
    @Inject lateinit var reminders: ReminderStorageRepository
    @Inject lateinit var reconciliation: NotificationReconciliation
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var shifts: TimeShifts
    @Inject lateinit var round: DailyRound
    @Inject lateinit var answering: ReminderAnswering

    private val WAIT = 10_000L
    private val box = Uuid.random()
    private lateinit var course: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        // Новый телефон на Android 14: точные будильники приложению не разрешены.
        world.alarms.canBeExact = false
        world.start(reminders, reconciliation, transactions, shifts, round, answering)
        runBlocking {
            database.storySetting()
            database.packageRepository().add(pack(id = box, name = "Эналаприл", quantity = tablets("30"), form = TABLET_FORM))
            course = database.treatmentStarted(moscow(thursday, 7, 0), "Эналаприл", box, thursday, listOf(LocalTime.of(8, 0), LocalTime.of(23, 50)))
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * «Могут опаздывать» — разрешил — точная постановка на вечерний приём. 23:50 на посадке —
     * «Отложить» через полночь. Телефон перезагрузился в самолёте в 00:02: проход дня сделал
     * вчерашний приём пропуском, и отсрочка ушла вместе с ним. В Новосибирске при входе — попап
     * пропущенного, «Принял»; сводка по новому дню телефона; утренний приём — в названный по Москве
     * момент. Назавтра вчерашняя сводка из шторки открывает сегодняшний «День».
     *
     * Стережёт: строка о точности уходит после разрешения, а точная постановка встаёт; отсрочка
     * через полночь не теряет приём молча — он приходит последним шансом; смена зоны не сдвигает
     * приём; сводка ведёт в сегодняшний день.
     */
    @Test
    fun ruslanFliesFourZonesEastAndHisTreatmentKeepsMoscowTime() {
        exactAlarmsAreExplainedAndThenPlaced()
        heSnoozesAtBoardingAcrossMidnight()
        thePhoneRebootsInThePlaneAndTheSnoozedIntakeBecomesTheLastChance()
        heLandsInNovosibirskAndTheMorningIntakeKeepsMoscowTime()
        yesterdaysDigestOpensToday()
    }

    private fun exactAlarmsAreExplainedAndThenPlaced() {
        runBlocking { world.enter() }
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { shown("День") }
        compose.onNodeWithText("День").performClick()
        compose.waitUntil(WAIT) { shown("Напоминания могут опаздывать") }

        world.alarms.canBeExact = true
        comeBack()
        compose.waitUntil(WAIT) { !shown("Напоминания могут опаздывать") }
        val morning = runBlocking { database.intakeOn(course, thursday, 8, 0) }
        compose.waitUntil(WAIT) { world.alarms.exactAt == morning.plannedAt }
        // Утренний — в своё время и точно; «Принял» из шторки.
        world.moveTo(morning.plannedAt.plusSeconds(1))
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }
        runBlocking { world.inShade(morning.id, NotificationAction.TAKE) }
        val evening = runBlocking { database.intakeOn(course, thursday, 23, 50) }
        compose.waitUntil(WAIT) { world.alarms.exactAt == evening.plannedAt }
    }

    private fun heSnoozesAtBoardingAcrossMidnight() {
        val evening = runBlocking { database.intakeOn(course, thursday, 23, 50) }
        world.moveTo(evening.plannedAt.plusSeconds(1))
        compose.waitUntil(WAIT) { world.cardUp(evening.id) }
        val snoozed = runBlocking { world.inShade(evening.id, NotificationAction.SNOOZE) }
        assertTrue("$snoozed", snoozed is ReminderAnswering.Response.Snoozed)
        compose.waitUntil(WAIT) { world.alarms.exactAt == (snoozed as ReminderAnswering.Response.Snoozed).at }
    }

    /**
     * 00:02, самолёт: телефон перезагрузился — проход дня и проход доставки. Вчерашний приём без
     * ответа стал пропуском, отсрочка ушла с ним, и будильник встал на утренний приём.
     */
    private fun thePhoneRebootsInThePlaneAndTheSnoozedIntakeBecomesTheLastChance() {
        world.moveTo(moscow(friday, 0, 2))
        runBlocking { world.enter() }
        val evening = runBlocking { database.intakeOn(course, thursday, 23, 50) }
        assertEquals(IntakeStatus.MISSED, evening.status)
        val morning = runBlocking { database.intakeOn(course, friday, 8, 0) }
        compose.waitUntil(WAIT) { world.alarms.exactAt == morning.plannedAt }
    }

    /**
     * 09:30 в Новосибирске (05:30 по Москве). Вход — попап пропущенного: вечерний приём он выпил
     * в самолёте, «Принял». Сводка — по новому дню телефона. Утренний приём звучит в 08:00 по
     * Москве, то есть в 12:00 по Новосибирску, а не на четыре часа позже.
     */
    private fun heLandsInNovosibirskAndTheMorningIntakeKeepsMoscowTime() {
        world.moveTo(moscow(friday, 5, 30), novosibirsk)
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { shown(MISSED) }
        // Под попапом — «День» со своими «Принял»: кнопка берётся у строки попапа. Строка найдена
        // по времени телефона (23:50 по Москве — 03:50 в Новосибирске); по какому дню её подписывать
        // в чужой зоне — открытый вопрос модели, и история его не закрепляет.
        compose.onNode(hasText("Принял") and hasAnyAncestor(hasClickAction() and hasText("03:50", substring = true) and hasText("пропущен"))).performClick()
        compose.waitUntil(WAIT) { !shown(MISSED) }
        assertEquals(IntakeStatus.TAKEN, runBlocking { database.intakeOn(course, thursday, 23, 50).status })
        compose.waitUntil(WAIT) { digestFor(friday) != null }

        val morning = runBlocking { database.intakeOn(course, friday, 8, 0) }
        assertEquals(morning.plannedAt, world.alarms.exactAt)
        world.moveTo(morning.plannedAt.plusSeconds(1), novosibirsk)
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }
        runBlocking { world.inShade(morning.id, NotificationAction.TAKE) }
    }

    /** Назавтра он нажимает вчерашнюю сводку, оставшуюся в шторке, — открывается сегодняшний день. */
    private fun yesterdaysDigestOpensToday() {
        val saturday = friday.plusDays(1)
        world.moveTo(moscow(saturday, 5, 30), novosibirsk)
        runBlocking { world.enter() }
        // Пятничный вечерний приём без ответа — сначала он, как всегда при входе.
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(MISSED) }

        val yesterdays = requireNotNull(digestFor(friday))
        compose.onNodeWithText("Аптечки").performClick()
        runBlocking { world.tap(yesterdays) }
        compose.waitUntil(WAIT) { shown("03.04.2027 · сегодня") }
    }

    private fun digestFor(day: LocalDate) = world.shade.shown.lastOrNull {
        it.kind == NotificationKind.DAILY_DIGEST && it.target == NotificationTarget.DayPlan(day)
    }

    private fun comeBack() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        runBlocking { world.enter() }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val MISSED = "Без ответа за прошлые дни"
    }
}
