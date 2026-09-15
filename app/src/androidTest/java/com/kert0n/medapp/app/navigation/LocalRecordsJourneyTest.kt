package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
 * Сквозной путь без сети: с пустого списка человек заводит аптечку и видит её там, где будет
 * искать.
 *
 * Эта проверка отвечает не за поведение экрана — за **достижимость**: недостижимый экран и
 * функция без экрана это одна и та же ошибка (AGENTS). В прошлый заход она окупилась сразу:
 * два экрана были написаны, проверены поодиночке — и не подключены к оболочке.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalRecordsJourneyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hilt.inject()
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @Test
    fun aShelfIsCreatedFromTheEmptyListAndShowsUpThere() {
        compose.onNodeWithText("Завести аптечку").performClick()

        compose.onNodeWithText("Новая аптечка").assertIsDisplayed()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Место хранения (необязательно)").performTextInput("В ванной")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        // Записанное уводит с формы само: человек заводил полку, а не форму.
        compose.waitUntil {
            compose.onAllNodesWithText("Найти лекарство во всех аптечках").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("В ванной").assertIsDisplayed()
        compose.onNodeWithText("Пока пусто").assertIsDisplayed()
    }
}
