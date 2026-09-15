package com.kert0n.medapp.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Оболочка: пять мест внизу, и место, где стоит человек, переживает пересоздание (PLAN H3, J3).
 * Уровень «Экран» по PLAN J1.
 */
@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allFivePlacesAreThereAndTheFirstIsSelected() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        for (tab in listOf("Аптечки", "План", "Сканер", "Аналитика", "Настройки")) {
            compose.onNodeWithText(tab).assertIsDisplayed()
        }
        compose.onNodeWithText("Аптечки").assertIsSelected()
    }

    @Test
    fun tappingAPlaceGoesThere() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        compose.onNodeWithText("Аналитика").performClick()

        compose.onNodeWithText("Аналитика").assertIsSelected()
        compose.onNodeWithText("Аптечки").assertIsNotSelected()
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
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Настройки").assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Настройки").assertIsSelected()
        compose.onNodeWithText("Аптечки").assertIsNotSelected()
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

        compose.onNodeWithText("Аптечки").assertIsDisplayed()
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        compose.onNodeWithText("Этот экран ещё не готов.").assertIsDisplayed()
    }
}
