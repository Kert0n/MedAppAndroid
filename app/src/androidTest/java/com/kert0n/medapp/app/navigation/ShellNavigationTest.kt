package com.kert0n.medapp.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Оболочка: пять мест внизу, и место, где стоит человек, переживает пересоздание (PLAN H3, J3).
 * Уровень «Экран» по PLAN J1.
 *
 * Окно поднимается `HiltTestActivity`, а не пустой `ComponentActivity`: за вкладкой «Аптечки»
 * стоит настоящий экран, а он берёт свою `ViewModel` у графа — обычная активность такой не
 * отдаёт и роняет проверку на первом же показе.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun allFivePlacesAreThereAndTheFirstIsSelected() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        for (tab in listOf("Аптечки", "План", "Сканер", "Отчёты", "Опции")) {
            compose.tab(tab).assertIsDisplayed()
        }
        compose.tab("Аптечки").assertIsSelected()
    }

    @Test
    fun tappingAPlaceGoesThere() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        compose.tab("Отчёты").performClick()

        compose.tab("Отчёты").assertIsSelected()
        compose.tab("Аптечки").assertIsNotSelected()
    }

    /**
     * Поворот и смерть процесса — не действие человека: место остаётся тем же. Состояние держит
     * навигация, и восстанавливается оно из `SavedStateHandle`; [StateRestorationTester] проходит
     * ровно этот путь — сохранение и восстановление, а не построение заново.
     *
     * Красная проверка: собрать `NavHostController` мимо `rememberNavController` (без
     * `rememberSaveable`) — вкладка возвращается к «Аптечкам».
     */
    @Test
    fun thePlaceSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MedAppTheme { MedAppShell() } }
        compose.tab("Опции").performClick()
        compose.tab("Опции").assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        compose.tab("Опции").assertIsSelected()
        compose.tab("Аптечки").assertIsNotSelected()
    }

    /**
     * Крупный шрифт разметку не ломает: подписи и содержимое остаются на экране при двойном
     * масштабе (PLAN H3, J3).
     */
    @Test
    fun largeFontKeepsEverythingOnScreen() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                MedAppTheme { MedAppShell(Modifier.fillMaxSize()) }
            }
        }

        compose.tab("Аптечки").assertIsDisplayed()
        compose.tab("Опции").assertIsDisplayed()
    }
}

/**
 * Место внизу, а не любая надпись с тем же словом: за вкладкой «Аптечки» стоит экран, чей
 * заголовок называется так же, и без этого проверка спорила бы с содержимым вместо навигации.
 */
private fun ComposeTestRule.tab(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and isSelectable())
