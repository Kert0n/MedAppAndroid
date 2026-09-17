package com.kert0n.medapp.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.settings.LanguageChoice
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Язык (PLAN H3 №27): отмечен текущий, строка нажимается целиком и говорит чтецу, что она — выбор. */
@RunWith(AndroidJUnit4::class)
class LanguageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: LanguageChoice? = null

    private fun row(name: String) = compose.onNode(isSelectable() and hasText(name))

    /** Отмечен текущий язык и только он: две отметки или ни одной не говорят, на каком языке приложение. */
    @Test
    fun onlyTheCurrentLanguageIsMarked() {
        compose.setContent { MedAppTheme { LanguageScreen(LanguageChoice.SYSTEM, onChoose = { chosen = it }, onBack = {}) } }

        row("Как в системе").assertIsDisplayed().assertIsSelected()
        row("Русский").assertIsNotSelected()
        row("English").assertIsNotSelected()
    }

    /** Нажатие на строку выбирает её язык: иначе строка выглядит выбором, а ничего не меняет. */
    @Test
    fun tappingARowChoosesItsLanguage() {
        compose.setContent { MedAppTheme { LanguageScreen(LanguageChoice.SYSTEM, onChoose = { chosen = it }, onBack = {}) } }

        row("English").performClick()

        assertEquals(LanguageChoice.ENGLISH, chosen)
    }
}
