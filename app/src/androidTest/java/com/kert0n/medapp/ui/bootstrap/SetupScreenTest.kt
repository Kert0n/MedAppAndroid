package com.kert0n.medapp.ui.bootstrap

import com.kert0n.medapp.presentation.bootstrap.AppStartState

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * До регистрации человек видит экран настройки, а не пустой список, притворяющийся работающим
 * приложением (PLAN C3, H3 №1).
 */
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: AppStartState, onRetry: () -> Unit = {}) {
        compose.setContent { MedAppTheme { SetupScreen(state, onRetry) } }
    }

    @Test
    fun whileCheckingThereIsNothingToPress() {
        show(AppStartState.Checking)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
    }

    @Test
    fun aFailedSetupSaysWhyAndOffersRetry() {
        var retried = 0
        show(AppStartState.Setup(Unavailability.NO_CONNECTION)) { retried++ }

        compose.onNodeWithText("Нет связи с сервером. Проверьте подключение.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()

        assertEquals(1, retried)
    }

    /**
     * Утрата ключа повтором не лечится: ключ не откроется оттого, что нажали ещё раз, и вторая
     * учётка поверх локальных данных не заводится молча (PLAN G2).
     *
     * Красная проверка: показать утрату ключа как обычный отказ — появится «Повторить».
     */
    @Test
    fun aLostKeyIsExplainedWithoutAPointlessRetry() {
        show(AppStartState.KeyLost)

        compose.onNodeWithText("Ключ этого устройства", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
    }
}
