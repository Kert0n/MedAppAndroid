package com.kert0n.medapp.ui.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.scan.ScannerCamera
import com.kert0n.medapp.presentation.scan.ScannerUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сканер (PLAN H3 №24): что человек видит, когда камеры нет, и куда он может уйти. Что происходит
 * с прочитанным кодом, проверяет `ScannerViewModelTest`, а сам разбор — `CodeFormatTest`.
 *
 * Видоискатель здесь не показывается: живая камера в проверке — это комната эмулятора, а не наш
 * экран.
 */
@RunWith(AndroidJUnit4::class)
class ScannerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var allowed = 0
    private var settings = 0

    private fun show(state: ScannerUiState) {
        compose.setContent {
            MedAppTheme {
                ScannerScreen(
                    state = state,
                    onCode = {},
                    onAllow = { allowed++ },
                    onOpenSettings = { settings++ }
                )
            }
        }
    }

    /**
     * Без камеры сканера нет и просить нечего (T-45): экран говорит об этом и не предлагает
     * разрешений, которых система не даст.
     */
    @Test
    fun aDeviceWithoutACameraIsToldSoAndAsksForNothing() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT))

        compose.onNodeWithText("На этом устройстве нет камеры. Заводите коробки на полке — там же, где и без кода.")
            .assertIsDisplayed()
        compose.onNodeWithText("Разрешить камеру").assertIsNotDisplayed()
    }

    /** До первой просьбы человеку предлагают дать разрешение: диалог ещё будет. */
    @Test
    fun anUnaskedQuestionOffersToAllow() {
        show(ScannerUiState(camera = ScannerCamera.UNASKED))

        compose.onNodeWithText("Разрешить камеру").performClick()

        assertEquals(1, allowed)
    }

    /**
     * После отказа диалога больше не будет, и кнопка «Разрешить» ничего бы не открыла: экран ведёт
     * туда, где разрешение действительно меняют.
     *
     * Красная проверка: оставить «Разрешить» — человек нажимает и ничего не происходит.
     */
    @Test
    fun aRefusalLeadsToSystemSettings() {
        show(ScannerUiState(camera = ScannerCamera.REFUSED))

        compose.onNodeWithText("Открыть настройки").performClick()

        assertEquals(1, settings)
    }

    /**
     * Чужой код назван словами: молчание неотличимо от сломанной камеры. Это **единственное**, что
     * сканер говорит от себя: узнанное он показывает не здесь, а на том экране, куда ведёт.
     */
    @Test
    fun anAlienCodeIsNamedInWords() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT, isUnsupported = true))

        compose.onNodeWithText("Этот код не поддерживается: на упаковках лекарств стоит DataMatrix.")
            .assertIsDisplayed()
    }

}
