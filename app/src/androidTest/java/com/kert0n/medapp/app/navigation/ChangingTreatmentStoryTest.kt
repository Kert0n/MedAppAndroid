package com.kert0n.medapp.app.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
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
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Петра** (PLAN U1 «история человека»). У него лечение, которое всё время меняется:
 * порядок коробок, доза от врача, отданная соседу пачка — и, наконец, отмена.
 *
 * Начинается она с готового назначения: сам Пётр записывал его когда-то, и переписывать этот путь
 * незачем — его проходит история Ирины. Здесь важна **вторая половина жизни лечения**, та, где
 * человек правит уже идущее: изменение остаётся тем же эпизодом, а опасное спрашивает.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChangingTreatmentStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    /**
     * Ожидание с запасом: история ждёт состояния, пришедшего из базы, и секунды по умолчанию на
     * это не хватает — краснело бы не о коде, а о занятости машины.
     */
    private val WAIT = 5_000L

    @Before
    fun setUp() {
        // Начало лечения спрашивает разрешение на уведомления, и системный диалог закрыл бы окно.
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            val packages = database.packageRepository()
            packages.add(pack(id = PACK, name = "Домашняя пачка", quantity = tablets("20"), form = TABLET_FORM))
            packages.add(pack(id = OTHER_PACK, name = "Дачная пачка", quantity = tablets("10"), form = TABLET_FORM))
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
        compose.onNodeWithText("План").performClick()
    }

    @Test
    fun peterStartsHisTreatmentThenChangesItAndFinallyCancelsIt() {
        hisPrescriptionIsWrittenAndTwoBoxesAreAttached()
        heStartsTheTreatment()
        heSpendsTheOpenedBoxFirst()
        theDoctorRaisesTheDose()
        heGivesTheOtherBoxAway()
        andInTheEndTheTreatmentIsCancelled()
    }

    /** Назначение и две коробки записаны заранее: путь к ним проходит история Ирины. */
    private fun hisPrescriptionIsWrittenAndTwoBoxesAreAttached() = runBlocking {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Ибупрофен")
        // Завязка отвечает за себя сама: не записалась — падаем здесь, а не через три экрана
        // непонятным ожиданием.
        val written = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.now(MOSCOW))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(5)),
                CourseDrafting.Edit.Attach(OTHER_PACK, Doses(5))
            )
        )
        assertTrue("завязка не записалась: $written", written is CourseDrafting.Outcome.Saved)
    }

    /** Лечение начинается с карточки: первое, что она говорит, — чем оно обеспечено. */
    private fun heStartsTheTreatment() {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ибупрофен").performClick()
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Начать лечение").performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("обеспечен", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Коробок хватает на все десять приёмов, и карточка говорит, до какого дня.
        compose.onNodeWithText("нужно 10 приёмов · обеспечен до", substring = true).assertIsDisplayed()
    }

    /** Початую коробку жалко: Пётр ставит её первой в расход и записывает состав. */
    private fun heSpendsTheOpenedBoxFirst() {
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Подключить ещё").fetchSemanticsNodes().isNotEmpty()
        }

        // Перестановка доступна и пальцем (перетаскиванием за ручку), и действием доступности;
        // проверка берёт второе — оно называет себя словом.
        compose.onNodeWithText("Дачная пачка").moveIt("Выше")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Правка не записана", substring = true).fetchSemanticsNodes().isEmpty()
        }
        // Записанный состав виден на самом экране, и порядок в нём — тот, что выбрал Пётр:
        // початая коробка стоит первой. Без этого шаг проходил бы и со сломанной перестановкой.
        val rows = compose.onAllNodes(hasText("Дачная пачка") or hasText("Домашняя пачка"))
            .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }
        assertEquals("Дачная пачка", rows.first().config[SemanticsProperties.Text].first().text)
    }

    /** Врач поднял дозу: лечение остаётся тем же эпизодом, а не заводится заново. */
    private fun theDoctorRaisesTheDose() {
        back()
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Изменить").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Доза").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Доза").performScrollTo().performTextReplacement("3")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()

        // Карточка показывает назначение словами — и новая доза стоит в нём, а эпизод тот же.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("3 таблетка", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Дачную пачку он отдал соседу — отвязка идущего лечения спрашивает, прежде чем снять бронь. */
    private fun heGivesTheOtherBoxAway() {
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Подключить ещё").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onAllNodesWithText("Отвязать")[0].performClick()
        compose.onNodeWithText("Отвязать пачку?").assertIsDisplayed()
        compose.onNode(hasText("Отвязать") and hasAnyAncestor(isDialog())).performClick()
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()
    }

    /** И наконец — отмена: с вопросом, и карточка после неё говорит, чем лечение кончилось. */
    private fun andInTheEndTheTreatmentIsCancelled() {
        back()
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Отменить лечение").performClick()

        compose.onNodeWithText("Отменить лечение?").assertIsDisplayed()
        compose.onNodeWithText("Будущие приёмы уйдут", substring = true).assertIsDisplayed()
        // Подтверждение названо тем же словом, что и действие в меню, — берём кнопку из диалога.
        compose.onNode(hasText("Отменить лечение") and hasAnyAncestor(isDialog())).performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Отменён", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun back() = compose.onNodeWithContentDescription("Назад").performClick()

    /** Действие доступности строки — по его названию: у ручки перестановки их два, «Выше» и «Ниже». */
    private fun SemanticsNodeInteraction.moveIt(label: String) {
        val move = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { move.action() }
        compose.waitForIdle()
    }
}
