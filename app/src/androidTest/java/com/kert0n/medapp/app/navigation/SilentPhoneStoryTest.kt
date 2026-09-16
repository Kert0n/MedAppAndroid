package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.TestPermissions
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
 * **Истории молчавшего телефона** (PLAN U1 «история человека», `docs/истории.md`).
 *
 * Уведомления — самая непроверяемая руками часть приложения: между «приложение должно было сказать»
 * и «человек пришёл сам» проходят дни, а подгадать их живому человеку нечем. В проверке эти дни
 * назначаются строкой, поэтому историй здесь три, и каждая — свой человеческий поворот.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SilentPhoneStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 5_000L

    /** Сегодняшний день человека — от него отсчитываются «три дня назад» и «вчера». */
    private val today: LocalDate = LocalDate.now(MOSCOW)

    @Before
    fun setUp() {
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            database.packageRepository()
                .add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        }
    }

    @After
    fun tearDown() {
        TestPermissions.reset()
    }

    /**
     * **Максим** — «отмахнулся от разрешения» (`docs/истории.md`): он ждал сигнала три дня, а
     * телефон молчал, и на третий открыл приложение сам.
     *
     * Стережёт: день говорит **почему** молчал и **что** пропущено, и отвечается это там же —
     * страница листается только вперёд, и без полки прошлые приёмы недосягаемы.
     */
    @Test
    fun maximForgotToAllowNotificationsAndMeetsThreeSilentDays() {
        TestPermissions.notifications = false
        val courseId = treatmentStartedThreeDaysAgo()
        val overdue = overdueIntakes(courseId)
        assertTrue("завязка без пропусков: проверять нечего", overdue.size >= 2)
        promiseWeFailedToTell(overdue)
        start()

        // Первое, что он видит на дне, — почему телефон молчал.
        openTheDay()
        compose.onNodeWithText("Напоминания не приходят").assertIsDisplayed()

        // И что именно пропустил: обязательства стоят полкой, каждое со своим днём.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("О чём не смогли напомнить").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Принял").onFirst().performClick()

        // Ответ записан по-настоящему: коробка похудела на дозу.
        compose.waitUntil(WAIT) { runBlocking { spent() == tablets("18") } }
    }

    /**
     * **Ольга** — «попала пальцем не туда» (`docs/истории.md`): тянулась к «Принял», попала в
     * «Пропустил», а таблетку выпила.
     *
     * Стережёт: у ошибки виден след — слово «пропущен» в строке, — и есть путь назад: «Принял» у
     * пропущенного остаётся, доза уехала вперёд, а не пропала (PLAN D6).
     */
    @Test
    fun olgaPressedSkippedByMistakeAndFixesIt() {
        val courseId = treatmentStartedToday()
        start()
        openTheDay()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Пропустил").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Пропустил").onFirst().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("пропущен").fetchSemanticsNodes().isNotEmpty() }

        // Ошибку видно, и рядом с ней — «Принял»: отказ не запирает пункт навсегда.
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            val taken = database.intakeRepository().ofCourse(courseId)
                .filterIsInstance<CourseIntake>()
                .count { it.status == IntakeStatus.TAKEN }
            assertEquals("исправленный приём не записан", 1, taken)
        }
    }

    /**
     * **Тимур** — «три дня без связи» (`docs/истории.md`): лекарства пил, а отмечать было негде.
     *
     * Стережёт две меры: на полке — только наступившее (о завтрашнем скажут завтра), и отвечается
     * оно по одному — за каждой дозой стоит таблетка, и врать о них разом легче, чем по одной
     * (PLAN D6).
     */
    @Test
    fun timurReturnsFromATripToWhatHasPiledUp() {
        val courseId = treatmentStartedThreeDaysAgo()
        val overdue = overdueIntakes(courseId)
        promiseWeFailedToTell(overdue)
        start()
        openTheDay()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("О чём не смогли напомнить").fetchSemanticsNodes().isNotEmpty()
        }
        val piled = compose.onAllNodesWithText("Принял").fetchSemanticsNodes().size
        assertTrue("накопившегося не видно: $piled", piled >= 2)

        // Отвечает он по одному, и записывается ровно один приём: накопившееся не сваливается
        // в кучу «отметить всё» — за каждым стоит своя доза (PLAN D6).
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) { runBlocking { takenCount(courseId) == 1 } }
        // Остальное ждёт на той же полке: разговор не окончен.
        compose.onNodeWithText("О чём не смогли напомнить").assertIsDisplayed()
    }

    /** Лечение, начатое три дня назад: пункты прошлых дней остались без ответа и стали пропусками. */
    private fun treatmentStartedThreeDaysAgo(): Uuid = runBlocking {
        val started = Instant.now().minus(3, ChronoUnit.DAYS)
        val past = Scenarios(database, started)
        val created = past.courseDrafting.create("Нурофен")
        val saved = past.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = today.minusDays(3), times = listOf(LocalTime.of(9, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved
        past.courseActivation.activate(saved.draft.id, saved.draft.revision)
        // Сегодняшними часами прошлое приводится в порядок: неотвеченные дни стали пропусками.
        Scenarios(database, Instant.now()).courseUpkeep.keepUp()
        saved.draft.id
    }

    /** Лечение, начатое сегодня: у него один неотвеченный пункт — тот, о котором история. */
    private fun treatmentStartedToday(): Uuid = runBlocking {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = today, times = listOf(LocalTime.of(9, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        saved.draft.id
    }

    /** Пункты, чьё время уже прошло: о них и не смогли сказать. */
    private fun overdueIntakes(courseId: Uuid): List<CourseIntake> = runBlocking {
        database.intakeRepository().ofCourse(courseId)
            .filterIsInstance<CourseIntake>()
            .filter { it.slot.at.isBefore(Instant.now()) }
            .sortedBy { it.slot.at }
    }

    /** Обязательства «сказать не смогли» — такие же, какие заводит сверка (PLAN D8). */
    private fun promiseWeFailedToTell(intakes: List<CourseIntake>) = runBlocking {
        Scenarios(database, Instant.now()).reminderPromising.promise(
            intakes.map {
                Reminder(
                    key = NotificationKey.intake(it.id, NotificationKind.INTAKE_DUE),
                    target = NotificationTarget.Intake(it.id),
                    dueAt = it.slot.at
                )
            }
        )
    }

    private suspend fun spent() = database.packageRepository().find(PACK)?.quantity

    private suspend fun takenCount(courseId: Uuid) = database.intakeRepository().ofCourse(courseId)
        .filterIsInstance<CourseIntake>()
        .count { it.status == IntakeStatus.TAKEN }

    private fun start() {
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    /** Путь к странице дня: место «План» и его состояние «День». */
    private fun openTheDay() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("День").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("День").performClick()
    }
}
