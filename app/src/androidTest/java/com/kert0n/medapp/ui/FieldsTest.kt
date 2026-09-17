package com.kert0n.medapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Поля, общие всем формам. Дату называет календарь, и открывается он **всем полем**: «зоны
 * нажатия от 48 dp» (PLAN H3 «Дизайн») — про зону, а не про высоту рамки, а живого в поле было
 * 24 dp значка (C1 «Поле-выбор открывается всем полем»).
 */
@RunWith(AndroidJUnit4::class)
class FieldsTest {

    @get:Rule
    val compose = createComposeRule()

    private var picked: LocalDate? = LocalDate.of(2027, 3, 10)

    private fun showDate(value: LocalDate? = LocalDate.of(2027, 3, 10)) {
        compose.setContent {
            MedAppTheme { DateField(label = "С какого дня", value = value, onPick = { picked = it }) }
        }
    }

    /**
     * Нажатие по самому полю открывает календарь. Без этого правила попасть можно только в значок:
     * на 360 dp и при крупном шрифте палец промахивается, и поле выглядит неживым.
     */
    @Test
    fun theWholeFieldOpensTheCalendar() {
        showDate()

        compose.onNodeWithText("10.03.2027").performClick()

        compose.onNodeWithText("Выбрать").assertIsDisplayed()
    }

    /** Крестик очищает дату — и календаря при этом не открывает: нажатие тратит на себя он сам. */
    @Test
    fun theCrossClearsTheDateWithoutOpeningTheCalendar() {
        showDate()

        compose.onNodeWithContentDescription("Убрать дату").performClick()

        assertNull(picked)
        compose.onNodeWithText("Выбрать").assertDoesNotExist()
    }

    /** У значка календаря остаётся своё имя: экранный чтец называет действие, а не «кнопка». */
    @Test
    fun theCalendarIconKeepsItsName() {
        showDate(value = null)

        compose.onNodeWithContentDescription("Выбрать дату").assertIsDisplayed()
    }

    private var asked = ""

    private fun showSearch() {
        compose.setContent { MedAppTheme { LaggingSearch { asked = it } } }
    }

    private val searchField get() = compose.onNode(hasSetTextAction())

    /**
     * Набранное после очистки остаётся набранным. Пустой запрос, вернувшийся от модели на кадр
     * позже первой буквы, обрезал курсор в начало строки: вторая буква вставала перед первой, и
     * «123» после очистки набиралось как «231» — искалось тоже оно.
     */
    @Test
    fun typingAfterClearingKeepsTheOrderOfLetters() {
        showSearch()
        type("123")

        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        type("123")

        assertEquals("123", asked)
        searchField.assert(hasText("123"))
    }

    /** То же стирание с клавиатуры: очистка крестиком — не единственный способ опустошить поле. */
    @Test
    fun typingAfterErasingKeepsTheOrderOfLetters() {
        showSearch()
        type("12")

        searchField.performTextClearance()
        type("123")

        assertEquals("123", asked)
        searchField.assert(hasText("123"))
    }

    /** Сброс запроса снаружи забирает и набранное: просьба пришла не от набора, а от экрана. */
    @Test
    fun theOwnerStillClearsTheField() {
        var query by mutableStateOf("тера")
        compose.setContent {
            MedAppTheme { SearchField(value = query, onValueChange = { query = it }, label = "Поиск") }
        }

        compose.runOnIdle { query = "" }

        searchField.assert(hasText(""))
    }

    /** Буквы набираются по одной: клавиатура шлёт каждую отдельно, и дефект живёт между ними. */
    private fun type(text: String) {
        text.forEach { searchField.performTextInput(it.toString()) }
    }
}

/**
 * Поиск, чей запрос возвращается в поле не в тот же кадр. Так он и живёт на экранах: набранное
 * уходит в модель, та считает список вне главного потока и отвечает состоянием (PLAN H4), а до
 * ответа поле показывают прежним запросом.
 */
@Composable
private fun LaggingSearch(onAsk: (String) -> Unit) {
    var shown by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    SearchField(
        value = shown,
        onValueChange = {
            asked = it
            onAsk(it)
        },
        label = "Поиск"
    )
    LaunchedEffect(asked) { shown = asked }
}
