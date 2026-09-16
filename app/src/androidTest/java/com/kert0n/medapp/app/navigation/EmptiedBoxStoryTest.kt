package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
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
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Дмитрия** — «коробка кончилась на приёме» (`docs/истории.md`, U5).
 *
 * Рассказ — там; здесь его путь по приложению. Ставится в проверке только решающий вечер: второй
 * месяц лечения к делу не идёт, а одна коробка, которой на всё лечение не хватает, — идёт.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EmptiedBoxStoryTest {

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
            // Ровно на один приём: следующую дозу брать будет неоткуда.
            database.packageRepository()
                .add(pack(id = PACK, name = "Аторис", quantity = tablets("2"), form = TABLET_FORM))
        }
    }

    @Test
    fun dmitryTakesTheLastTabletAndTheTreatmentSaysItHasNoSourceLeft() {
        val courseId = treatmentLongerThanItsBox()
        start()

        heTakesTheLastTablet()
        theBoxIsGoneAndTheTreatmentSaysSo(courseId)
        andWhatHeTookFromItIsStillWritten()
    }

    /** Лечение на четыре приёма, а коробка — на один: обычное дело у долгого лечения. */
    private fun treatmentLongerThanItsBox(): Uuid = runBlocking {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Аторвастатин")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = LocalDate.now(MOSCOW), times = listOf(LocalTime.of(9, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(1))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        saved.draft.id
    }

    /** Вечерний приём как всегда — одно нажатие, и таблеток в коробке не осталось. */
    private fun heTakesTheLastTablet() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("День").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("День").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Принял").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Кончившаяся коробка кончается приёмом, а лечение теряет её тем же решением (PLAN D3, D5) — и
     * **говорит** об этом нехваткой. Молчащее лечение Дмитрий разоблачит завтра вечером у пустой
     * полки: он ходит в аптеку тогда, когда приложение скажет, что пора.
     */
    private fun theBoxIsGoneAndTheTreatmentSaysSo(courseId: Uuid) {
        runBlocking {
            compose.waitUntil(WAIT) { runBlocking { database.packageRepository().find(PACK) == null } }
            assertNull("кончившаяся коробка осталась живой", database.packageRepository().find(PACK))
            val plan = requireNotNull(database.courseRepository().findPlan(courseId))
            assertTrue("лечение держится за коробку, которой нет", plan.sources.isEmpty())
        }
        compose.onNodeWithText("Курсы").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Не хватает", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Приём переживает вещь, о которой рассказывает: имя и единица лежат в самом факте (PLAN D3,
     * D6). Пропади приёмы вместе с коробкой — Дмитрий не докажет даже себе, что лечился.
     */
    private fun andWhatHeTookFromItIsStillWritten() {
        compose.onNodeWithText("Аторвастатин").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Вся история").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Вся история").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("2 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Аторис", substring = true).assertIsDisplayed()
    }

    private fun start() {
        compose.setContent { MedAppTheme { MedAppShell() } }
    }
}
