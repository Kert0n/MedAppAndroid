package com.kert0n.medapp.ui

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * Показывать нечего — три случая, и человек различает их по тому, что ему делать: ждать,
 * повторить или завести первое. Повтор предлагается там, где он осмыслен.
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

        assertEquals(1, retried)
    }

    /**
     * Отказ в пропуске повтором тем же не лечится (PLAN G2): кнопки нет, и обещания повтора
     * тоже.
     *
     * Красная проверка: предлагать повтор при любой причине — этот случай краснеет.
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

        assertEquals(1, created)
    }

    /**
     * Экран, чьё чтение не удалось, заменён словами о причине, «Повторить» и «Назад»: без этого
     * человек смотрит на вечную загрузку и выйти может только из приложения. Повтор возвращает
     * экран, как только чтение прошло.
     */
    @Test
    fun aScreenThatCouldNotReadSaysSoAndComesBackOnRetry() {
        val reading = com.kert0n.medapp.presentation.ScreenReading()
        var reads = 0
        kotlinx.coroutines.runBlocking {
            reading.load(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) {
                reads++
                if (reads == 1) throw IllegalStateException("disk I/O error")
            }.join()
        }
        var back = 0
        compose.setContent { MedAppTheme { Readable(reading, onBack = { back++ }) { androidx.compose.material3.Text("Содержимое") } } }

        // Слова — из ресурсов: экран смотрят и на английском крупном BigScaled.
        val words = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(words.getString(com.kert0n.medapp.R.string.failure_device_storage)).assertIsDisplayed()
        compose.onNodeWithContentDescription(words.getString(com.kert0n.medapp.R.string.action_back)).performClick()
        assertEquals(1, back)

        compose.onNodeWithText(words.getString(com.kert0n.medapp.R.string.action_retry)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Содержимое").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, reads)
    }
}
