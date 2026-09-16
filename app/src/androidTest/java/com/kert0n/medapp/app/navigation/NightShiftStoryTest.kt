package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.TestPermissions
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.treatmentStarted
import com.kert0n.medapp.fixture.intakeOn
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Светланы — медсестра суточных смен** (`docs/истории.md`, U5).
 *
 * Три лечения на одном телефоне, шесть пунктов в день; разрешение на уведомления смахнуто. Время
 * двигает рассказ ([StoryWorld]), обязательства заводят календарь и проход дня, а проверка только
 * нажимает — как Светлана.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NightShiftStoryTest {

    /** Понедельник, на который пришлась её смена; лечение она завела перед сменой, в 07:00. */
    private val shiftDay: LocalDate = LocalDate.of(2027, 3, 8)

    private val world = StoryWorld.begin(at(shiftDay, 7, 0), MOSCOW)

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

    private val antibiotic = Uuid.random()
    private val pressure = Uuid.random()
    private val vitamin = Uuid.random()
    private lateinit var antibioticCourse: Uuid
    private lateinit var pressureCourse: Uuid
    private lateinit var vitaminCourse: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering)
        // В раздевалке, в перчатках: системный вопрос смахнут не читая.
        TestPermissions.notifications = false
        runBlocking {
            database.storySetting()
            val boxes = database.packageRepository()
            boxes.add(pack(id = antibiotic, name = "Амоксиклав", quantity = tablets("30"), form = TABLET_FORM))
            boxes.add(pack(id = pressure, name = "Лозартан", quantity = tablets("30"), form = TABLET_FORM))
            boxes.add(pack(id = vitamin, name = "Витамин D", quantity = tablets("30"), form = TABLET_FORM))
            antibioticCourse = database.treatmentStarted(at(shiftDay, 7, 0), "Амоксиклав", antibiotic, shiftDay, listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)))
            pressureCourse = database.treatmentStarted(at(shiftDay, 7, 0), "Лозартан", pressure, shiftDay, listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))
            vitaminCourse = database.treatmentStarted(at(shiftDay, 7, 0), "Витамин D", vitamin, shiftDay, listOf(LocalTime.of(10, 0)))
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * Смена прошла, телефон молчал. Светлана отвечает за вчера **попапом при входе** — в прошлые дни
     * плана не уйти, — узнаёт на «Дне», почему молчал телефон, разрешает уведомления, смахивает одно
     * напоминание и находит его назавтра в том же попапе, глушит канал «Приёмы», и полночь проходит
     * при открытой странице.
     *
     * Стережёт: попап пропущенного — последний шанс и сегодняшнего в нём нет; крестик оставляет
     * пропуски навсегда; причины немоты названы; снятый запрет не сыплет в шторку вчерашнее;
     * смахнутое без ответа возвращается; страница дня сама переходит через полночь.
     */
    @Test
    fun svetlanaAnswersForTheSilentShiftAndKeepsHerFathersPillsInOrder() {
        theMorningAfterTheShift()
        sheAnswersWhatSheTookAndLetsTheRestBeMissed()
        theDayTellsWhyThePhoneWasSilent()
        sheAllowsNotificationsAndYesterdayStaysOutOfTheShade()
        aSwipedReminderComesBackAsTheLastChance()
        aMutedChannelIsNamed()
        midnightTurnsTheOpenPageOver()
    }

    private fun theMorningAfterTheShift() {
        world.moveTo(at(shiftDay.plusDays(1), 7, 30))
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { shown(MISSED) }
    }

    /** Дневной антибиотик и витамин отца она давала — «Принял»; остальное — крестик. */
    private fun sheAnswersWhatSheTookAndLetsTheRestBeMissed() {
        val day = "08.03.2027"
        confirmInPopup("Амоксиклав", "$day · 14:00")
        confirmInPopup("Витамин D", "$day · 10:00")
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(MISSED) }

        runBlocking {
            assertEquals(IntakeStatus.TAKEN, statusOn(antibioticCourse, shiftDay, 14))
            assertEquals(IntakeStatus.TAKEN, statusOn(vitaminCourse, shiftDay, 10))
            assertEquals(IntakeStatus.MISSED, statusOn(antibioticCourse, shiftDay, 20))
            assertEquals(IntakeStatus.MISSED, statusOn(pressureCourse, shiftDay, 21))
            // Крестик — навсегда: вчерашнее больше не обещано сказать.
            assertTrue(reminders.awaiting(com.kert0n.medapp.domain.notification.NoticeDelivery.IN_APP_BANNER).isEmpty())
        }
    }

    private fun theDayTellsWhyThePhoneWasSilent() {
        openTheDay()
        compose.waitUntil(WAIT) { shown("Напоминания не приходят") }
        // Сегодняшний утренний приём — в дне, и один раз.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("08:00").fetchSemanticsNodes().size == 1 }
    }

    /** Разрешила и вернулась: строки нет, утренний приём звучит в свой час, вчерашнего в шторке нет. */
    private fun sheAllowsNotificationsAndYesterdayStaysOutOfTheShade() {
        TestPermissions.notifications = true
        comeBack()
        compose.waitUntil(WAIT) { !shown("Напоминания не приходят") }

        world.moveTo(at(shiftDay.plusDays(1), 8, 1))
        runBlocking { world.settle() }
        val morning = runBlocking { database.intakeOn(antibioticCourse, shiftDay.plusDays(1), 8) }
        compose.waitUntil(WAIT) { world.cardUp(morning.id) }
        assertTrue(
            "в шторке нет вчерашнего: пропуск говорится попапом",
            world.shade.shown.none { it.kind == NotificationKind.INTAKE_MISSED || it.dueAt.isBefore(at(shiftDay.plusDays(1), 0, 0)) }
        )
    }

    /** Смахнула утреннее, не ответив, — назавтра оно ждёт её в попапе, а не пропадает молча. */
    private fun aSwipedReminderComesBackAsTheLastChance() {
        val morning = runBlocking { database.intakeOn(antibioticCourse, shiftDay.plusDays(1), 8) }
        runBlocking { world.shade.dismiss(NotificationKey.intake(morning.id, NotificationKind.INTAKE_DUE)) }

        world.moveTo(at(shiftDay.plusDays(2), 7, 30))
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("Амоксиклав"))).onLast().performScrollToNode(rowWith("Амоксиклав", "09.03.2027 · 08:00"))
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(MISSED) }
    }

    /** Ночью в палате заглушила канал «Приёмы»: утром «День» говорит именно об этом. */
    private fun aMutedChannelIsNamed() {
        TestPermissions.muted = setOf(NotificationChannel.INTAKES)
        comeBack()
        compose.waitUntil(WAIT) { shown("Напоминания о приёмах выключены") }
        compose.onNodeWithText("Напоминания не приходят").assertDoesNotExist()
    }

    /**
     * 23:59, «День» открыт: она отвечает за вечерний приём отца, и полночь переводит страницу сама.
     * Ответ на границе суток — один исход: проход нового дня не делает принятое пропуском.
     */
    private fun midnightTurnsTheOpenPageOver() {
        world.moveTo(at(shiftDay.plusDays(2), 23, 59))
        compose.waitUntil(WAIT) { shown("10.03.2027 · сегодня") }
        val evening = rowWith("Лозартан", "21:00")
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("Лозартан"))).onLast().performScrollToNode(evening)
        compose.onNode(hasText("Принял") and hasAnyAncestor(evening)).performClick()
        compose.waitUntil(WAIT) { runBlocking { statusOn(pressureCourse, shiftDay.plusDays(2), 21) } == IntakeStatus.TAKEN }

        world.moveTo(at(shiftDay.plusDays(3), 0, 1))
        compose.waitUntil(WAIT) { shown("11.03.2027 · сегодня") }
        compose.onNodeWithText("11.03.2027 · сегодня").assertIsDisplayed()
        runBlocking {
            world.enter()
            assertEquals(IntakeStatus.TAKEN, statusOn(pressureCourse, shiftDay.plusDays(2), 21))
        }
    }

    private suspend fun statusOn(course: Uuid, day: LocalDate, hour: Int) = database.intakeOn(course, day, hour).status

    private fun rowWith(title: String, moment: String): SemanticsMatcher =
        // Карточка сливает тексты своих строк в себя: строка узнаётся по собственному тексту.
        hasClickAction() and hasText(title) and hasText(moment)

    /** «Принял» у строки попапа: строка находится своим лечением и днём со временем. */
    private fun confirmInPopup(title: String, moment: String) {
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(title))).onLast().performScrollToNode(rowWith(title, moment))
        compose.onNode(hasText("Принял") and hasAnyAncestor(rowWith(title, moment))).performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(rowWith(title, moment)).fetchSemanticsNodes().isEmpty() }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Вернулась из системных настроек: окно уходит в фон и возвращается. */
    private fun comeBack() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        runBlocking { world.enter() }
    }

    private fun openTheDay() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { shown("День") }
        compose.onNodeWithText("День").performClick()
    }

    private fun at(day: LocalDate, hour: Int, minute: Int) = moscow(day, hour, minute)

    private companion object {
        const val MISSED = "Без ответа за прошлые дни"
    }
}
