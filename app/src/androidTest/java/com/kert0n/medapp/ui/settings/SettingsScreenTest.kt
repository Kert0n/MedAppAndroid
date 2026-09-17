package com.kert0n.medapp.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.settings.SettingsFormError
import com.kert0n.medapp.presentation.settings.SettingsFormPresentationDTO
import com.kert0n.medapp.presentation.settings.SettingsUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Форма настроек (PLAN H3 №27): что человек видит и что нажимает. Что при этом записывается,
 * проверяет `SettingsViewModelTest` — форма об этом не знает.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var edited: SettingsFormPresentationDTO? = null
    private var saved = 0

    private val filled = SettingsFormPresentationDTO(snoozeMinutes = "15", coverageThresholdDays = "3")

    private fun show(state: SettingsUiState, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MedAppTheme {
                    SettingsScreen(state = state, onEdit = { edited = it }, onSave = { saved++ }, onBack = {})
                }
            }
        }
    }

    /** Пока записанное не прочитано — загрузка, а не форма с умолчаниями, которую можно сохранить. */
    @Test
    fun whileLoadingThereIsNothingToSave() {
        show(SettingsUiState.Loading)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    /** Переключатель нажимается всей строкой и отдаёт форму с новым значением. */
    @Test
    fun aSwitchRowTogglesByItsWords() {
        show(SettingsUiState.Editing(filled))

        compose.onNodeWithText("Напоминания о приёмах").assertIsOn().performClick()

        assertEquals(false, edited?.intakeReminders)
    }

    /** Отказ поля виден у подвала — там, куда смотрят, нажав «Сохранить». */
    @Test
    fun aRejectedFieldIsExplained() {
        show(SettingsUiState.Editing(filled.copy(snoozeMinutes = "0"), error = SettingsFormError.Input.SNOOZE_NOT_FORWARD))

        compose.onNodeWithText("Отложить можно только вперёд").assertIsDisplayed()
    }

    /** Не легло — сказано, и «Сохранить» остаётся: человек нажмёт ещё раз. */
    @Test
    fun aLostWriteIsToldAndSaveStays() {
        show(SettingsUiState.Editing(filled, error = SettingsFormError.NotSaved))

        compose.onNodeWithText("Не удалось записать — прежние настройки действуют").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /** Крупный шрифт не уносит подвал с экрана: «Сохранить» видно без прокрутки (PLAN J3). */
    @Test
    fun largeFontKeepsSaveOnScreen() {
        show(SettingsUiState.Editing(filled), fontScale = 2f)

        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        compose.onNodeWithText("Сообщать о чужих изменениях").performScrollTo().assertIsOn()
    }

    /** Выключенная сводка отключает и поле времени — печатать время сводке, которой нет, незачем. */
    @Test
    fun digestOffDisablesItsTime() {
        show(SettingsUiState.Editing(filled.copy(digest = false)))

        compose.onNodeWithText("Присылать сводку на день").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("Выбрать время").performScrollTo().assertIsDisplayed()
    }
}
