package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
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
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Максима** (PLAN U1 «история человека»). Телефон вчера промолчал — напомнить не вышло, —
 * и приложение говорит об этом при встрече: попапом о сроке годности и полкой «о чём не смогли
 * напомнить» на странице дня.
 *
 * Проверка идёт его путём целиком, потому что набор и есть про **встречу**: новость о вещи, ответ на
 * вчерашний приём и ответ на сегодняшний живут в трёх разных местах, а день у человека один.
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
            val packages = database.packageRepository()
            packages.add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
            // Коробка, у которой срок кончается сегодня: о ней и будет попап.
            packages.add(
                pack(
                    id = OTHER_PACK,
                    name = "Цетрин",
                    quantity = tablets("10"),
                    form = TABLET_FORM,
                    expiresOn = ExpiryDate(LocalDate.now(MOSCOW))
                )
            )
        }
    }

    @Test
    fun maximMeetsWhatThePhoneCouldNotTellHim() {
        hisTreatmentRunsAndTheAppFailedToRemind()
        atEntryTheExpiryNewsMeetsHim()
        theDayTellsHimWhatItCouldNotAnnounce()
        andHeAnswersItRightThere()
    }

    /** Лечение идёт, о приёме сказать не смогли — обязательство осталось невыполненным. */
    private fun hisTreatmentRunsAndTheAppFailedToRemind() = runBlocking {
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
        // Новость о сроке годности: её приложение тоже не сказало — попап и есть эта встреча.
        scenarios.reminderPromising.promise(
            listOf(
                Reminder(
                    key = NotificationKey(NotificationKind.EXPIRY_TODAY, OTHER_PACK.toString()),
                    target = NotificationTarget.PackageCard(OTHER_PACK),
                    dueAt = Instant.now()
                )
            )
        )
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    /** Первое, что Максим видит, — попап о сроке: строкой в списке эта новость потерялась бы. */
    private fun atEntryTheExpiryNewsMeetsHim() {
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Цетрин").assertIsDisplayed()
        // Прочитал — закрыл крестиком: подтверждать новость нечем.
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isEmpty()
        }
    }

    /** На странице дня приёмы, о которых не смогли сказать, стоят своей полкой и первыми. */
    private fun theDayTellsHimWhatItCouldNotAnnounce() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("День").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("День").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("О чём не смогли напомнить").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Отвечает он там же — теми же двумя кнопками, что и на обычном пункте дня. */
    private fun andHeAnswersItRightThere() {
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Записан настоящий приём, а не отметка на экране: коробка похудела на дозу.
        runBlocking {
            assertTrue(
                "приём не записан: коробка цела",
                database.packageRepository().find(PACK)?.quantity == tablets("18")
            )
        }
    }
}
