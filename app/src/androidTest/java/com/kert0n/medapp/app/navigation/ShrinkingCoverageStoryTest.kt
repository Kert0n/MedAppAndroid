package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.courseRepository
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Кати** — «таблеток оказалось меньше» (`docs/истории.md`, U5).
 *
 * Рассказ — там; здесь её путь по приложению. Стережёт: после пересчёта нехватка названа числом и
 * днём, а расписание не тронуто — сколько и когда принимать, решил врач (PLAN C1, D5).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShrinkingCoverageStoryTest {

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
            database.packageRepository()
                .add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        }
    }

    @Test
    fun katyaFindsFewerTabletsThanSheCountedOnAndTheAppSaysItPlainly() {
        herTreatmentWasCoveredToTheEnd()
        thenSheRecountsTheBoxAndFindsItHalfEmpty()
        theCourseCardSaysHowManyDosesAreMissing()
        butTheScheduleStaysAsTheDoctorSaidIt()
    }

    /** Десять приёмов по две таблетки, коробка на двадцать: всё сходилось. */
    private fun herTreatmentWasCoveredToTheEnd() {
        runBlocking { started() }
        compose.setContent { MedAppTheme { MedAppShell() } }
        openTheCourse()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("обеспечен", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Пересчёт — не расход: Катя не принимала эти таблетки, она узнала, что их нет. Приложение
     * поэтому и спрашивает «сколько сейчас в коробке», а не «сколько вы приняли» (PLAN D3).
     */
    private fun thenSheRecountsTheBoxAndFindsItHalfEmpty() = runBlocking {
        Scenarios(database, Instant.now()).packageAdjusting.adjust(
            PACK,
            PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("6"))
        )
    }

    /**
     * Нехватка названа числом и днём — «не хватает N приёмов с …»: значок без слов сообщением не
     * является, а «что-то не так» человеку ничего не говорит (PLAN H3 №14).
     */
    private fun theCourseCardSaysHowManyDosesAreMissing() {
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Не хватает", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Семь приёмов из десяти без запаса: шести таблеток хватает на три дозы.
        compose.onAllNodesWithText("Не хватает 7 приёмов", substring = true).fetchSemanticsNodes()
            .ifEmpty { error("нехватка названа не числом") }
    }

    /**
     * И главное: расписание осталось прежним. Приложение не «сократило курс до возможного» и не
     * передвинуло приёмы — это решение врача и человека, а не следствие пустой коробки (C1).
     */
    private fun butTheScheduleStaysAsTheDoctorSaidIt() = runBlocking {
        val plan = database.courseRepository().let { courses ->
            courses.planIds().firstNotNullOf { courses.findPlan(it) }
        }
        assertEquals("назначение переписали за человека", Doses(10), plan.totalDoses)
    }

    private suspend fun started(): Uuid {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = LocalDate.now(MOSCOW), times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(10))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    private fun openTheCourse() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Нурофен").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").performClick()
    }
}
