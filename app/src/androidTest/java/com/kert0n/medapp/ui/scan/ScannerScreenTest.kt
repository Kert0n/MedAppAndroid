package com.kert0n.medapp.ui.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.scan.ScannerCamera
import com.kert0n.medapp.presentation.scan.ScannerNotice
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

    private var manual = 0
    private var joined = 0
    private var allowed = 0
    private var settings = 0

    private fun show(state: ScannerUiState) {
        compose.setContent {
            MedAppTheme {
                ScannerScreen(
                    state = state,
                    onCode = {},
                    onAllow = { allowed++ },
                    onOpenSettings = { settings++ },
                    onJoin = { joined++ },
                    onManual = { manual++ }
                )
            }
        }
    }

    /**
     * Ручной ввод стоит внизу **всегда**, а не только при беде: код бывает затёрт, а коробка —
     * без кода вовсе, и уходить с места за этим незачем.
     */
    @Test
    fun manualEntryIsAlwaysThere() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT))

        compose.onNodeWithText("Ввести вручную").performClick()

        assertEquals(1, manual)
    }

    /**
     * Без камеры сканера нет и просить нечего (T-45): экран говорит об этом и не предлагает
     * разрешений, которых система не даст.
     */
    @Test
    fun aDeviceWithoutACameraIsToldSoAndAsksForNothing() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT))

        compose.onNodeWithText("На этом устройстве нет камеры. Коробки заводятся вручную.").assertIsDisplayed()
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

    /** Чужой код назван словами: молчание неотличимо от сломанной камеры. */
    @Test
    fun anAlienCodeIsNamedInWords() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT, notice = ScannerNotice.UNSUPPORTED))

        compose.onNodeWithText("Этот код не поддерживается: на упаковках лекарств стоит DataMatrix.")
            .assertIsDisplayed()
    }

    /**
     * Приглашение сканер не принимает сам, а ведёт туда, где вступают: исходы вступления живут в
     * одном месте, а ключ не едет в маршрут (PLAN G3, C1).
     */
    @Test
    fun anInvitationLeadsToJoining() {
        show(ScannerUiState(camera = ScannerCamera.ABSENT, notice = ScannerNotice.INVITATION))

        compose.onNodeWithText("Это приглашение в аптечку.").assertIsDisplayed()
        compose.onNodeWithText("Присоединиться").performClick()

        assertEquals(1, joined)
    }
}
