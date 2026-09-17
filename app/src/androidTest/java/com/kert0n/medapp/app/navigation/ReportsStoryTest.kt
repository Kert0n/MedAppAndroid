package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.millilitres
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
 * **История Ксении — месяц лечения в цифрах** (`docs/истории.md`, U10).
 *
 * Ксения прошла короткий курс от спины до конца, пила таблетку от аллергии мимо лечения и лечит
 * кашель сиропом, который ещё идёт. В конце месяца она читает все три отчёта подряд — и каждый
 * шаг здесь стережёт свою беду: **закончившееся лечение из «истрачено» не пропадает** (записи
 * эпизодов живут вечно), в «расход» оно, наоборот, не попадает (будущего у него нет), разовые
 * приёмы считаются наравне с курсовыми, единицы не смешиваются, а коробки без цены не становятся
 * бесплатными.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReportsStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 10_000L

    /** Зона у завязки и у приложения одна: день оно считает по часам устройства. */
    private val zone: ZoneId = ZoneId.systemDefault()

    private val syrup = Uuid.random()
    private val allergy = Uuid.random()
    private lateinit var backCourse: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            val packages = database.packageRepository()
            // Цена есть у одной коробки из трёх: две другие человек не оценивал, и это «неизвестно».
            packages.add(
                pack(
                    id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM,
                    category = "Обезболивающие", price = Money(BigDecimal("320"))
                )
            )
            packages.add(pack(id = allergy, name = "Цетрин", quantity = tablets("10"), form = TABLET_FORM, category = "От аллергии"))
            packages.add(pack(id = syrup, name = "Амброксол", quantity = millilitres("100"), form = CAPSULE_FORM, category = "От кашля"))
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @Test
    fun kseniaReadsHerMonthInNumbers() {
        herBackTreatmentIsOverButItsPillsWereDrunk()
        sheAlsoTookOneJustLikeThat()
        andTheCoughSyrupIsStillRunning()
        theSummaryTellsWhatSheHasAndWhatSheDoesNotKnow()
        spentShowsTheFinishedTreatmentAndTheOneOffIntakes()
        theRowOfATreatmentLeadsToItsCard()
        aheadShowsOnlyTheRunningTreatment()
    }

    /**
     * Курс от спины пройден **до конца**: все назначенные дозы выпиты, и лечение закрылось само —
     * плана больше нет, а запись эпизода осталась. Путь к назначению проходит история Ирины; здесь
     * важен уже законченный эпизод.
     */
    private fun herBackTreatmentIsOverButItsPillsWereDrunk() = runBlocking {
        val scenarios = Scenarios(database, Instant.now(), zone)
        val created = scenarios.courseDrafting.create("Спина, две недели")
        val written = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = LocalDate.now(zone), times = listOf(LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(21, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(3)),
                CourseDrafting.Edit.Attach(PACK, Doses(3))
            )
        )
        assertTrue("завязка не записалась: $written", written is CourseDrafting.Outcome.Saved)
        val draft = (written as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        backCourse = draft.id

        val items = database.intakeRepository().ofCourse(backCourse).filterIsInstance<CourseIntake>()
        for (item in items) {
            scenarios.intakeConfirmation.confirm(item.id, PACK, dose("1"), Instant.now())
        }
        // Лечение кончилось само: доз впереди не осталось, и плана больше нет (PLAN D5).
        assertNull("лечение должно было закончиться", database.courseRepository().findPlan(backCourse))
    }

    /** Таблетка от аллергии — мимо всякого лечения: это тоже расход, и отчёт о нём знает (H6). */
    private fun sheAlsoTookOneJustLikeThat() = runBlocking {
        Scenarios(database, Instant.now(), zone)
            .unplannedIntakeRecording.record(allergy, dose("1"), Instant.now())
    }

    /** Кашель ещё лечится: сироп идёт, и у него есть будущее — в миллилитрах, а не в таблетках. */
    private fun andTheCoughSyrupIsStillRunning() = runBlocking {
        val scenarios = Scenarios(database, Instant.now(), zone)
        val created = scenarios.courseDrafting.create("Кашель")
        val written = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose(millilitres("10"))),
                CourseDrafting.Edit.SetForm(CAPSULE_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = LocalDate.now(zone), times = listOf(LocalTime.of(10, 0), LocalTime.of(22, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(syrup, Doses(6))
            )
        )
        assertTrue("сироп не записался: $written", written is CourseDrafting.Outcome.Saved)
        val draft = (written as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
    }

    /** Сводка: три коробки, цена названа у одной, и об остальных сказано прямо. */
    private fun theSummaryTellsWhatSheHasAndWhatSheDoesNotKnow() {
        openReports()
        see("3 упаковки")
        see("320 ₽")
        see("У 2 упаковок цена не указана")
        see("Обезболивающие")
    }

    /**
     * **Главный шаг истории.** Лечение от спины кончилось, а выпитые по нему таблетки остались
     * выпитыми: запись эпизода вечна, и отчёт читает её, а не план.
     */
    private fun spentShowsTheFinishedTreatmentAndTheOneOffIntakes() {
        compose.onNodeWithText("Истрачено").performClick()
        see("По лечениям")
        see("Спина, две недели")
        see("3 приёма")
        see("Разовые приёмы")
        see("Цетрин")
    }

    /** Строка лечения ведёт на его карточку — ту же, что и у идущего, только с исходом. */
    private fun theRowOfATreatmentLeadsToItsCard() {
        compose.onNode(hasClickAction() and hasText("Спина, две недели", substring = true)).performClick()
        see("Завершён")
        back()
        see("Истрачено")
    }

    /** У законченного лечения будущего нет: в расходе стоит только идущий сироп. */
    private fun aheadShowsOnlyTheRunningTreatment() {
        compose.onNodeWithText("Расход").performClick()
        see("если все приёмы состоятся")
        see("Кашель")
        see("60 мл")
        compose.onAllNodesWithText("Спина, две недели").assertCountEquals(0)
    }

    private fun openReports() {
        see("Отчёты")
        compose.onAllNodesWithText("Отчёты").onLast().performClick()
        see("По категориям")
    }

    private fun see(text: String) = compose.waitUntil(WAIT) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
