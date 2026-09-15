package com.kert0n.medapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Общие поля экранов. Здесь проверяется то, что у них общего и что легко потерять: длинное меню
 * не складывает все свои пункты сразу.
 */
@RunWith(AndroidJUnit4::class)
class FieldsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(count: Int) {
        val options = (1..count).map { "Форма $it" }
        compose.setContent {
            MedAppTheme {
                PickerField(
                    label = "Форма выпуска",
                    selected = null,
                    options = options,
                    optionText = { it },
                    onPick = {}
                )
            }
        }
        compose.onNodeWithText("Форма выпуска").performClick()
    }

    /** Пока пунктов немного, выбор остаётся меню: окно ради трёх строк — лишний шаг. */
    @Test
    fun aShortChoiceStaysAMenu() {
        show(count = 3)

        compose.onNodeWithText("Форма 3").assertIsDisplayed()
        compose.onNodeWithText("Отмена").assertDoesNotExist()
    }

    /**
     * Длинный список показывается окном со списком, который держит только видимое.
     *
     * Меню Material меряет содержимое `IntrinsicSize.Max` — ленивый список внутрь него не
     * встаёт вовсе, а обычная колонка складывает все пункты сразу: на 208 формах встроенного
     * словаря (issue #36) это шестьсот с лишним узлов, и прокрутка спотыкается.
     *
     * Красная проверка: показать длинный список тем же меню — проверка увидит последний пункт,
     * не прокрутив ни разу, потому что сложены будут все.
     */
    @Test
    fun aLongChoiceKeepsOnlyWhatIsVisible() {
        show(count = 208)

        compose.onNodeWithText("Форма 1").assertIsDisplayed()
        assertTrue(
            "выбор сложил все пункты сразу",
            compose.onAllNodesWithText("Форма 208").fetchSemanticsNodes().isEmpty()
        )
    }
}
