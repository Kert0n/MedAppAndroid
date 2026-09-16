package com.kert0n.medapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
