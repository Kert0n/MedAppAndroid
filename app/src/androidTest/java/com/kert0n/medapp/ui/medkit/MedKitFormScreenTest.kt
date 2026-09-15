package com.kert0n.medapp.ui.medkit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    private var saved = 0
    private var cancelled = 0

    private fun show(state: MedKitFormUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitFormScreen(state, onEdit = {}, onSave = { saved++ }, onCancel = { cancelled++ })
            }
        }
    }

    /**
     * Кнопка не гаснет: погашенная не объясняет, чего не хватает. Нажатие с пустым названием —
     * не «ничего не произошло», а названная причина.
     *
     * Красная проверка: гасить «Сохранить» — нажатия нет, и человек гадает, чего от него ждут.
     */
    @Test
    fun theSaveButtonStaysAliveAndTheRefusalNamesItsField() {
        show(MedKitFormUiState.Editing(MedKitFormPresentationDTO(), error = MedKitFormError.Input.NAME_EMPTY))

        compose.onNodeWithText("Название нужно: без него аптечку не отличить от других.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /** Полки нет — отказ, а не пустая форма: заполнять её было бы некуда. */
    @Test
    fun aShelfThatIsGoneIsToldAboutInsteadOfShowingAnEmptyForm() {
        show(MedKitFormUiState.Gone)

        compose.onNodeWithText("Этой аптечки больше нет.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    @Test
    fun cancellingWritesNothing() {
        show(MedKitFormUiState.Editing(MedKitFormPresentationDTO("Дача")))

        compose.onNodeWithText("Отмена").performClick()

        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    /**
     * Поворот и смерть процесса форму не теряют: напечатанное держит состояние, а не сам экран.
     *
     * Печатать обязательно: подставить готовое состояние прямо в `setContent` значит собрать
     * его заново после восстановления — тогда проверка проходит, даже если экран не отдал
     * наружу ни буквы.
     *
     * Красная проверка: заменить `rememberSaveable` на `remember` — «Дача» после пересоздания
     * исчезает.
     */
    @Test
    fun theFormSurvivesBeingRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MedAppTheme {
                var form by rememberSaveable(stateSaver = FORM) { mutableStateOf(MedKitFormPresentationDTO()) }
                MedKitFormScreen(
                    state = MedKitFormUiState.Editing(form),
                    onEdit = { form = it },
                    onSave = {},
                    onCancel = {}
                )
            }
        }
        compose.onNodeWithText("Название").performTextInput("Дача")
        compose.onNodeWithText("Место хранения (необязательно)").performTextInput("Сарай")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Дача").assertIsDisplayed()
        compose.onNodeWithText("Сарай").assertIsDisplayed()
    }

    private companion object {

        /**
         * Пересоздание переживает то, что положено в `rememberSaveable`. В приложении форму
         * держит `ViewModel`; здесь её держит проверка — тем же способом, иначе проверять
         * нечего.
         */
        val FORM: Saver<MedKitFormPresentationDTO, Any> = listSaver(
            save = { listOf(it.name, it.location) },
            restore = { MedKitFormPresentationDTO(it[0], it[1]) }
        )
    }
}
