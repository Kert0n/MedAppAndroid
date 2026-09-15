package com.kert0n.medapp.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Три состояния выглядят одинаково на всех экранах и различаются тем, что человек может сделать
 * (PLAN H3). Ожидание слышно экранному чтецу, отказ назван словами, повтор предлагается только
 * там, где он осмыслен.
 */
@RunWith(AndroidJUnit4::class)
class ScreenStatesTest {

    @get:Rule
    val compose = createComposeRule()

    /** Кружок без подписи для TalkBack — пустое место; подпись есть. */
    @Test
    fun waitingIsAudibleToTheScreenReader() {
        compose.setContent { MedAppTheme { LoadingState() } }

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
    }

    @Test
    fun failureIsNamedInWordsAndOffersRetry() {
        var retried = 0
        compose.setContent {
            MedAppTheme { ErrorMessage(Unavailability.NO_CONNECTION, onRetry = { retried++ }) }
        }

        compose.onNodeWithText("Нет связи с сервером. Проверьте подключение.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertHasClickAction().performClick()

        assertTrue(retried == 1)
    }

    /**
     * Отказ в пропуске повтором тем же не лечится (PLAN G2): кнопки нет, и обещания повтора тоже.
     *
     * Красная проверка: предложить повтор при любой причине — этот случай краснеет.
     */
    @Test
    fun aRefusedAccountIsNotOfferedAPointlessRetry() {
        compose.setContent {
            MedAppTheme { ErrorMessage(Unavailability.SERVER_REFUSED_US, onRetry = {}) }
        }

        compose.onNodeWithText("Сервер не принял учётную запись этого устройства.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
    }

    @Test
    fun emptinessOffersWhatToDoWhenThereIsSomethingToOffer() {
        var created = 0
        compose.setContent {
            MedAppTheme {
                EmptyState(text = "Аптечек пока нет", actionText = "Завести", onAction = { created++ })
            }
        }

        compose.onNodeWithText("Аптечек пока нет").assertIsDisplayed()
        compose.onNodeWithText("Завести").performClick()

        assertTrue(created == 1)
    }
}
