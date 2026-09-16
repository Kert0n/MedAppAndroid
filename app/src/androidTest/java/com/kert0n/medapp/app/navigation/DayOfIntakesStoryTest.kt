package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Анны** (PLAN U1 «история человека»). У неё идёт лечение, и весь её день — это ответы
 * на приёмы: утренний она подтверждает одним нажатием, вечерний пропускает, днём выпивает таблетку
 * просто так — а вечером хочет знать, что вообще было.
 *
 * Проверка идёт её путём целиком — от страницы дня до истории коробки, — потому что набор и есть
 * про **связь** этих мест: ответ в строке меняет число в коробке, а разовый приём и приём по плану
 * встречаются в одной истории.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DayOfIntakesStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    /** Ожидание с запасом: история ждёт состояния из базы, и секунды по умолчанию на это не хватает. */
    private val WAIT = 5_000L

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            database.packageRepository()
                .add(pack(id = PACK, name = "Цетрин", quantity = tablets("20"), form = TABLET_FORM))
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @Test
    fun annaAnswersHerDayAndThenReadsWhatHappened() {
        herTreatmentIsAlreadyRunning()
        inTheMorningSheConfirmsTheDoseInOneTap()
        atNoonSheTakesOneJustLikeThat()
        theEveningDoseSheSkips()
        andInTheEndSheReadsTheHistoryOfTheBox()
    }

    /**
     * Назначение записано и лечение идёт: путь к этому проходит история Ирины, а здесь важен день,
     * когда лечение уже идёт. Дважды в день — чтобы у Анны в одном дне было на что ответить
     * по-разному.
     */
    private fun herTreatmentIsAlreadyRunning() = runBlocking {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Цетрин")
        val written = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(
                        start = LocalDate.now(MOSCOW),
                        times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))
                    )
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        )
        assertTrue("завязка не записалась: $written", written is CourseDrafting.Outcome.Saved)
        val draft = (written as CourseDrafting.Outcome.Saved).draft
        val started = scenarios.courseActivation.activate(draft.id, draft.revision)
        assertTrue("лечение не началось: $started", database.courseRepository().findPlan(draft.id) != null)
    }

    /** Утро: страница дня, одно нажатие — и доза записана. Отвечать дважды на один приём незачем. */
    private fun inTheMorningSheConfirmsTheDoseInOneTap() {
        openTheDay()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Принял").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        // Записанное приходит чтением: строка называет время, в которое Анна ответила.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Днём Анна выпивает таблетку просто так — с карточки коробки, не теряя её из виду. */
    private fun atNoonSheTakesOneJustLikeThat() {
        openTheBox()
        // «Принять» — плавающая кнопка карточки; слово у неё живёт подписью значка.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithContentDescription("Принять").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Принять").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько принял").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сколько принял").performTextReplacement("1")
        closeSoftKeyboard()
        compose.onNodeWithText("Принять").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько принял").fetchSemanticsNodes().isEmpty() }
        // В места приложения человек возвращается назад: на карточке коробки нижних мест нет.
        back()
        back()
    }

    /** Вечерний приём Анна пропускает: это решение, и лечение о нём знает. */
    private fun theEveningDoseSheSkips() {
        openTheDay()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Пропустил").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Пропустил").onFirst().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("пропущен").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Вечером она смотрит, что вообще было: разовый приём и приём по плану — в одной истории. */
    private fun andInTheEndSheReadsTheHistoryOfTheBox() {
        openTheBox()
        compose.onNodeWithText("Приёмы из этой коробки").performScrollTo().performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("разово в", substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Путь к странице дня: место «План» и его состояние «День». Состояние место держит своё, но
     * ушедший в другое место возвращается к списку курсов — и день открывает заново.
     */
    private fun openTheDay() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("День").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("День").performClick()
    }

    /** Возврат кнопкой: у каждого экрана набора она в панели. */
    private fun back() = compose.onNodeWithContentDescription("Назад").performClick()

    /** Путь к коробке: места «Аптечки» → полка → сама коробка. */
    private fun openTheBox() {
        compose.onNodeWithText("Аптечки").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Домашняя").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Домашняя").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Цетрин").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Цетрин").onFirst().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
    }
}
