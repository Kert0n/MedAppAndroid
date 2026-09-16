package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
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
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.kert0n.medapp.fixture.intakeRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Два попапа в один вход** (PLAN C1 «Попап пропущенного», «Попап вне мест»; просьба владельца
 * 2026-09-16). В один день у коробки кончился срок, а вчерашний приём остался без ответа: при входе
 * человек должен прочесть обе новости, и **по одной**. Два окна разом перекрыли бы друг друга, и
 * крестик одного закрывал бы не то, что человек прочёл.
 *
 * Обе новости заводит механизм: проход дня отмечает вчерашний пропуск и сверяет сроки годности.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EntryPopupsTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 5_000L

    private val expiryTitle = "Сегодня истекает срок годности"
    private val missedTitle = "Без ответа за прошлые дни"

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
            // Нурофен — источник лечения и истекает сегодня; Цетрин — просто истекает сегодня.
            packages.add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.now(MOSCOW))))
            packages.add(pack(id = OTHER_PACK, name = "Цетрин", quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.now(MOSCOW))))
            startedYesterdayMorning()
            // Сегодняшний проход дня: вчерашнее стало пропуском, срок годности сверен.
            Scenarios(database, Instant.now(), ZoneId.systemDefault()).dailyRound.run()
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    private lateinit var courseId: kotlin.uuid.Uuid

    private suspend fun startedYesterdayMorning() {
        val yesterday = LocalDate.now(MOSCOW).minusDays(1)
        val started = Scenarios(database, yesterday.atTime(LocalTime.of(6, 0)).atZone(MOSCOW).toInstant())
        val created = started.courseDrafting.create("Амоксиклав")
        val saved = started.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = yesterday, times = listOf(LocalTime.of(8, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved
        started.courseActivation.activate(saved.draft.id, saved.draft.revision)
        courseId = saved.draft.id
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * **Сначала пропуски, потом срок — по одному окну** (решение владельца 2026-09-16). Из попапа
     * срока коробку выбрасывают; ответь человек за вчера после этого — ответить было бы уже нечем.
     * Крестик пропущенного открывает попап срока, его крестик — ничего.
     */
    @Test
    fun theMissedIntakesComeFirstAndTheExpiryNewsAfterThem() {
        compose.waitUntil(WAIT) { shown(missedTitle) }
        compose.onNodeWithText("Амоксиклав").assertIsDisplayed()
        compose.onNodeWithText(expiryTitle).assertDoesNotExist()

        compose.onNodeWithContentDescription("Закрыть").performClick()

        compose.waitUntil(WAIT) { shown(expiryTitle) }
        compose.onNodeWithText(missedTitle).assertDoesNotExist()
        compose.onNodeWithText("Цетрин").assertIsDisplayed()

        compose.onNodeWithContentDescription("Закрыть").performClick()

        compose.waitUntil(WAIT) { !shown(expiryTitle) }
        compose.onNodeWithText(missedTitle).assertDoesNotExist()
    }

    /**
     * **Уход с попапа пропущенного на карточку пункта его не закрывает**, и попап срока не встаёт
     * вместо него: вернувшись, человек видит тот же разговор.
     */
    @Test
    fun aTripToTheIntakeCardKeepsTheMissedIntakesWaiting() {
        compose.waitUntil(WAIT) { shown(missedTitle) }

        compose.onNodeWithText("Амоксиклав").performClick()
        compose.waitUntil(WAIT) { !shown(missedTitle) }
        compose.onNodeWithText(expiryTitle).assertDoesNotExist()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT) { shown(missedTitle) }
        compose.onNodeWithText(expiryTitle).assertDoesNotExist()
    }

    /**
     * **Сценарий владельца: ответил за вчера, выбросил истёкшую коробку.** Истекает сегодня как раз
     * источник вчерашнего приёма. Человек отвечает «Принял» за вчера — это записано из ещё живой
     * коробки, — затем из попапа срока идёт к ней и выбрасывает. Вернувшись, он не видит ни
     * попапа пропущенного с мёртвой строкой, ни выброшенной коробки в новости о сроке.
     */
    @Test
    fun answeringYesterdayThenThrowingTheExpiredSourceAwayLeavesNothingBehind() {
        compose.waitUntil(WAIT) { shown(missedTitle) }
        compose.onNodeWithText("Принял").performClick()

        compose.waitUntil(WAIT) { shown(expiryTitle) }
        compose.onNodeWithText("Нурофен").performClick()
        compose.waitUntil(WAIT) { shown("Сколько есть") }
        compose.onNodeWithContentDescription("Выбросить").performClick()
        compose.waitUntil(WAIT) { shown("Выбросить упаковку?") }
        compose.onNodeWithText("Выбросить").performClick()

        // Вернулся: пропущенного нет — за вчера отвечено; в новости о сроке остался только Цетрин,
        // выброшенного Нурофена в ней нет.
        compose.waitUntil(WAIT) { shown(expiryTitle) && !shown("Нурофен") }
        compose.onNodeWithText("Цетрин").assertIsDisplayed()
        compose.onNodeWithText(missedTitle).assertDoesNotExist()
        runBlocking {
            val taken = database.intakeRepository().ofCourse(courseId).filterIsInstance<com.kert0n.medapp.domain.intake.CourseIntake>()
                .count { it.status == com.kert0n.medapp.domain.intake.IntakeStatus.TAKEN }
            assertEquals("вчерашний приём записан из ещё живой коробки", 1, taken)
        }
    }
}
