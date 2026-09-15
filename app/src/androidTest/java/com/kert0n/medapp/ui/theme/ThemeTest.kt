package com.kert0n.medapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Палитра продукта своя при любом свете и на любом Android (PLAN H3). Проверяется тем, что тема
 * отдаёт, а не тем, что объявлено: подмена схемы динамическим цветом видна именно здесь.
 *
 * Красная проверка: вернуть теме динамический цвет или шаблонную сиреневую схему — оба случая
 * краснеют.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun schemeOf(darkTheme: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent {
            MedAppTheme(darkTheme = darkTheme) { scheme = MaterialTheme.colorScheme }
        }
        return scheme
    }

    @Test
    fun lightSchemeIsTheGreenPaletteOfThePlan() {
        val scheme = schemeOf(darkTheme = false)

        assertEquals(Color(0xFF1B6B4A), scheme.primary)
        assertEquals(Color(0xFFA8F0C6), scheme.primaryContainer)
        assertEquals(Color(0xFF3B6470), scheme.tertiary)
        assertEquals(Color(0xFFF6FBF3), scheme.surface)
        assertEquals(Color(0xFFBA1A1A), scheme.error)
        // Подложка панели навигации: незаданная роль приходит из умолчаний Material сиреневой,
        // и видно это только на экране — поэтому она названа здесь.
        assertEquals(Color(0xFFEAEFE7), scheme.surfaceContainer)
    }

    /** Тёмная схема — те же тона при другом свете, а не умолчания Material. */
    @Test
    fun darkSchemeKeepsTheSameHues() {
        val scheme = schemeOf(darkTheme = true)

        assertEquals(Color(0xFF8CD4AB), scheme.primary)
        assertEquals(Color(0xFFA8F0C6), scheme.onPrimaryContainer)
        assertEquals(Color(0xFF101410), scheme.surface)
        assertEquals(Color(0xFF1B6B4A), scheme.inversePrimary)
        assertEquals(Color(0xFF1D211C), scheme.surfaceContainer)
    }
}
