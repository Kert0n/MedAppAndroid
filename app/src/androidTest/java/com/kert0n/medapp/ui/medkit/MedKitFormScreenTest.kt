package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.medkit.MedKitFormError
import com.kert0n.medapp.presentation.medkit.MedKitFormPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitFormUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Форма аптечки (PLAN H3 №3): что человек видит и что он может нажать. Что при этом
 * записывается, проверяет `MedKitFormViewModelTest` — экран об этом не знает вовсе.
 */
@RunWith(AndroidJUnit4::class)
class MedKitFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val edited = mutableListOf<MedKitFormPresentationDTO>()
    private var saved = 0
    private var cancelled = 0

    private fun show(state: MedKitFormUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitFormScreen(
                    state = state,
                    onEdit = { edited += it },
                    onSave = { saved++ },
                    onCancel = { cancelled++ }
                )
            }
        }
    }

    @Test
    fun aNewShelfAsksForANameAndAPlace() {
        show(MedKitFormUiState.Editing())

        compose.onNodeWithText("Новая аптечка").assertIsDisplayed()
        compose.onNodeWithText("Название").assertIsDisplayed()
        compose.onNodeWithText("Место хранения (необязательно)").assertIsDisplayed()
    }

    /** Открытая на правку форма показывает записанное и называется правкой, а не заведением. */
    @Test
    fun aFormOpenedForEditingShowsWhatIsStored() {
        show(MedKitFormUiState.Editing(MedKitFormPresentationDTO("Домашняя", "Ванная"), isEditing = true))

        compose.onNodeWithText("Правка аптечки").assertIsDisplayed()
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("Ванная").assertIsDisplayed()
    }

    /** Полки нет — отказ словами, а не пустая форма, которую человек заполнил бы впустую. */
    @Test
    fun aShelfThatIsGoneIsSaidOutLoud() {
        show(MedKitFormUiState.Gone)

        compose.onNodeWithText("Этой аптечки больше нет.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    @Test
    fun whileReadingThereIsNothingToPress() {
        show(MedKitFormUiState.Loading)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    /**
     * Кнопка не гаснет: погашенная не объясняет, чего не хватает. Пустое название отдаётся
     * сценарию и возвращается названным отказом.
     *
     * Красная проверка: погасить «Сохранить» при пустом названии — нажатия не будет, и человек
     * останется без объяснения.
     */
    @Test
    fun theSaveButtonDoesNotGoDarkOnAnEmptyForm() {
        show(MedKitFormUiState.Editing())

        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /** Отказ назван словами и подсвечивает своё поле. */
    @Test
    fun aRefusalIsSpelledOut() {
        show(MedKitFormUiState.Editing(error = MedKitFormError.Input.NAME_EMPTY))

        compose.onNodeWithText("Название нужно: без него аптечку не отличить от других.").assertIsDisplayed()
    }

    /** Помеченная полка отказывает вслух, а не молчит (PLAN E1). */
    @Test
    fun aBusyShelfSaysWhyTheEditWasNotWritten() {
        show(MedKitFormUiState.Editing(error = MedKitFormError.Busy))

        compose.onNodeWithText(
            "Аптечка ждёт ответа сервера на другое решение — правку пока не записать."
        ).assertIsDisplayed()
    }

    @Test
    fun typingGoesBackToTheForm() {
        show(MedKitFormUiState.Editing())

        compose.onNodeWithText("Название").performTextInput("Дача")

        assertEquals("Дача", edited.single().name)
    }

    /** Отмена ничего не пишет и уводит с формы. */
    @Test
    fun cancellingWritesNothing() {
        show(MedKitFormUiState.Editing(MedKitFormPresentationDTO("Дача")))

        compose.onNodeWithText("Отмена").performClick()

        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    /**
     * Поворот и смерть процесса форму не теряют: что напечатано, держит состояние, а не сам
     * экран (PLAN J3).
     */
    @Test
    fun theFormSurvivesBeingRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MedAppTheme {
                MedKitFormScreen(
                    state = MedKitFormUiState.Editing(MedKitFormPresentationDTO("Дача", "Сарай")),
                    onEdit = {},
                    onSave = {},
                    onCancel = {}
                )
            }
        }

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Дача").assertIsDisplayed()
        compose.onNodeWithText("Сарай").assertIsDisplayed()
    }
}
