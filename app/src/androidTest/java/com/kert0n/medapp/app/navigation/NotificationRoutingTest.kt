package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.notification.NotificationOpening
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.platform.notifications.NotificationTargetExtras
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Уведомление ведёт туда, куда обещает (PLAN H3 «Уведомления на экране»): цель из намерения
 * применяется оболочкой, и в маршрут едут только идентификаторы.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotificationRoutingTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 5_000L

    @Before
    fun setUp() {
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            database.packageRepository()
                .add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        }
    }

    private fun open(target: NotificationTarget, action: NotificationAction? = null) {
        compose.setContent {
            MedAppTheme {
                MedAppShell(opening = NotificationOpening(target, action))
            }
        }
    }

    private fun started(expiresOn: LocalDate? = null): Uuid = runBlocking {
        expiresOn?.let {
            database.packageRepository().describe(
                PACK,
                pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM, expiresOn = ExpiryDate(it)).facts
            )
        }
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.now(MOSCOW))),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        saved.draft.id
    }

    /**
     * Приём ведёт на карточку пункта — и **по одному номеру**: столько же знает уведомление, и
     * второго знания у маршрута нет (PLAN G3).
     */
    @Test
    fun anIntakeNoticeOpensItsCard() {
        val courseId = started()
        val intakeId = runBlocking {
            database.intakeRepository().ofCourse(courseId).filterIsInstance<CourseIntake>().minBy { it.slot.at }.id
        }

        open(NotificationTarget.Intake(intakeId))

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Из какой коробки").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Из какой коробки").assertIsDisplayed()
    }

    /** Коробка ведёт на свою карточку: человек нажал на новость о ней и попал к ней. */
    @Test
    fun aPackageNoticeOpensItsCard() {
        open(NotificationTarget.PackageCard(PACK))

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сколько есть").assertIsDisplayed()
    }

    /** Сводка ведёт на страницу дня — сразу на день, а не на список курсов. */
    @Test
    fun theDigestOpensTheDayItself() {
        started()

        open(NotificationTarget.DayPlan(LocalDate.now(MOSCOW)))
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("сегодня", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * «Принял» из шторки по **просроченной** коробке не пишет молча: карточка открывается и
     * показывает вопрос, а записывает только ответ человека (PLAN D6, C1 «Принял из шторки»).
     */
    @Test
    fun takingFromTheTrayAsksBeforeItWrites() {
        val courseId = started(expiresOn = LocalDate.now(MOSCOW).minusDays(1))
        val intakeId = runBlocking {
            database.intakeRepository().ofCourse(courseId).filterIsInstance<CourseIntake>().minBy { it.slot.at }.id
        }

        open(NotificationTarget.Intake(intakeId), NotificationAction.TAKE)

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Прежде чем записать").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Всё равно принял").assertIsDisplayed()
        // До ответа не записано ничего: коробка целая.
        runBlocking {
            assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        }
    }
}
