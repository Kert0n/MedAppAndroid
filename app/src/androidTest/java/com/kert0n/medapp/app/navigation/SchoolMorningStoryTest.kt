package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.feature.notification.ReminderReadings
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.feature.notification.ReminderSubjects
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.intakeOn
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.treatmentStarted
import com.kert0n.medapp.platform.notifications.NotificationTargetExtras
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Ольги — утро с двумя школьниками** (`docs/истории.md`, U5).
 *
 * Шторка вместо экрана: «Отложить» мокрой рукой, «Пропустил» мимо пальца, «Принял» у плиты — и
 * наутро «Принял», которому нечего записать. Время двигает рассказ ([StoryWorld]).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SchoolMorningStoryTest {

    private val monday: LocalDate = LocalDate.of(2027, 3, 15)

    private val world = StoryWorld.begin(moscow(monday.minusDays(1), 20, 0), MOSCOW)

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var database: MedAppDatabase
    @Inject lateinit var connection: Connection
    @Inject lateinit var reminders: ReminderRecords
    @Inject lateinit var reconciliation: NotificationReconciliation
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var shifts: TimeShifts
    @Inject lateinit var round: DailyRound
    @Inject lateinit var answering: ReminderAnswering
    @Inject lateinit var subjects: ReminderSubjects

    private val WAIT = 10_000L
    private val iron = Uuid.random()
    private lateinit var course: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering, subjects, connection)
        runBlocking {
            database.storySetting()
            // Железо просрочено со вчера: Ольга знает и допивает, до аптеки завтра.
            database.packageRepository().add(
                pack(id = iron, name = "Железо", quantity = tablets("30"), form = TABLET_FORM, expiresOn = ExpiryDate(monday.minusDays(1)))
            )
            course = database.treatmentStarted(
                moscow(monday.minusDays(1), 20, 0), "Железо", iron, monday,
                listOf(LocalTime.of(7, 30), LocalTime.of(19, 30))
            )
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * Утро: «Отложить», через четверть часа снова, промах в «Пропустил» и исправление на «Дне», где
     * «Принял» один раз спрашивает о просроченном железе. Вечер: «Принял» у плиты пишет без
     * приложения и без вопроса. Наутро коробки нет, и «Принял» ничего не записывает, а приходит
     * «нужно ваше решение», ведущее на карточку; возврат из недавних на неё не возвращает.
     *
     * Стережёт: все три кнопки шторки делают своё без экрана; ошибку видно и её можно исправить;
     * о просрочке спрашивает экран, а шторка — нет; незаписанный приём не пропадает молча;
     * открытие применяется один раз.
     */
    @Test
    fun olgaAnswersFromTheShadeWithWetHandsAndAtTheStove() {
        theMorningRushInTheShade()
        sheFixesTheWrongButtonOnTheDay()
        theEveningTakeWritesWithoutTheApp()
        theNextMorningTakeHasNothingToWriteAndAsksForADecision()
    }

    private fun theMorningRushInTheShade() {
        val morning = runBlocking { database.intakeOn(course, monday, 7, 30) }
        world.moveTo(moscow(monday, 7, 30).plusSeconds(1))
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }

        // Мокрой рукой — «Отложить»: карточка уходит и возвращается через четверть часа.
        val snoozed = runBlocking { world.inShade(morning.id, NotificationAction.SNOOZE) }
        assertTrue("$snoozed", snoozed is ReminderAnswering.Response.Snoozed)
        compose.waitUntil(WAIT) { !world.cardUp(morning.id) }
        world.moveTo(moscow(monday, 7, 45).plusSeconds(1))
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }

        // Тянулась к «Принял», попала в «Пропустил».
        runBlocking { world.inShade(morning.id, NotificationAction.SKIP) }
        compose.waitUntil(WAIT) { runBlocking { database.intakeOn(course, monday, 7, 30).status } == IntakeStatus.MISSED }
    }

    /**
     * На остановке: «пропущен» видно, и «Принял» рядом исправляет. На экране приложение спрашивает
     * о просроченном железе — один раз, и «всё равно принял» пишет (решение владельца 2026-09-23).
     */
    private fun sheFixesTheWrongButtonOnTheDay() {
        world.moveTo(moscow(monday, 8, 10))
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { shown("День") }
        compose.onNodeWithText("День").performClick()
        val row = hasClickAction() and hasText("07:30") and hasText("пропущен")
        compose.waitUntil(WAIT) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Принял") and hasAnyAncestor(row)).performClick()
        compose.waitUntil(WAIT) { shown("Прежде чем записать") }
        compose.onNodeWithText("«Железо» просрочен", substring = true).assertExists()
        assertEquals(IntakeStatus.MISSED, runBlocking { database.intakeOn(course, monday, 7, 30).status })
        compose.onNodeWithText("Всё равно принял").performClick()
        compose.waitUntil(WAIT) { runBlocking { database.intakeOn(course, monday, 7, 30).status } == IntakeStatus.TAKEN }
        compose.onNodeWithText("Аптечки").performClick()
    }

    /** Вечер у плиты: «Принял» в шторке — записано, карточка ушла, приложение где было. */
    private fun theEveningTakeWritesWithoutTheApp() {
        val evening = runBlocking { database.intakeOn(course, monday, 19, 30) }
        world.moveTo(moscow(monday, 19, 30).plusSeconds(1))
        compose.waitUntil(WAIT) { world.cardUp(evening.id) }

        val taken = runBlocking { world.inShade(evening.id, NotificationAction.TAKE) }

        assertEquals(ReminderAnswering.Response.Done, taken)
        assertEquals(IntakeStatus.TAKEN, runBlocking { database.intakeOn(course, monday, 19, 30).status })
        compose.waitUntil(WAIT) { !world.cardUp(evening.id) }
        // Приложение не открывалось, и шторка о просроченной коробке не спрашивала.
        assertNull(world.opening.value)
        compose.onNodeWithText("Прежде чем записать").assertDoesNotExist()
    }

    /**
     * Муж отдал коробку соседке и убрал её из аптечки. «Принял» записать нечего — приходит «нужно
     * ваше решение», и его нажатие ведёт на карточку пункта. Система выгрузила приложение, Ольга
     * вернулась из недавних — открытие второй раз не применяется.
     */
    private fun theNextMorningTakeHasNothingToWriteAndAsksForADecision() {
        runBlocking { Scenarios(database, moscow(monday, 22, 0)).packageRemoval.remove(iron) }
        val tuesday = monday.plusDays(1)
        val morning = runBlocking { database.intakeOn(course, tuesday, 7, 30) }
        world.moveTo(moscow(tuesday, 7, 30).plusSeconds(1))
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }

        val asked = runBlocking { world.inShade(morning.id, NotificationAction.TAKE) }

        assertEquals(ReminderAnswering.Response.NeedsDecision, asked)
        assertEquals(IntakeStatus.PLANNED, runBlocking { database.intakeOn(course, tuesday, 7, 30).status })
        compose.waitUntil(WAIT) { world.cardUp(morning.id, NotificationKind.INTAKE_DECISION) }
        val decision = world.shade.shown.last { it.kind == NotificationKind.INTAKE_DECISION }
        runBlocking { world.tap(decision) }
        compose.waitUntil(WAIT) { shown("Назначено на 16.03.2027 в 07:30: 1 таблетка") }

        // Вернулась из недавних: система отдаёт окну то же намерение, а открытия оно не несёт.
        val sameIntent = NotificationTargetExtras.encode(decision.target)
        assertNull(NotificationTargetExtras.launchOpening(sameIntent, flags = 0, windowRestored = true))
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
