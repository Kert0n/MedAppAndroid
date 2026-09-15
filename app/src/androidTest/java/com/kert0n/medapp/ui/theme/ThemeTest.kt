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

    /**
     * Обе схемы за один показ: `setContent` у правила зовётся один раз за проверку, и второй
     * вызов падает «has already set content», а не сравнивает цвета.
     */
    private fun accents(): Pair<MedAppAccents, MedAppAccents> {
        lateinit var light: MedAppAccents
        lateinit var dark: MedAppAccents
        compose.setContent {
            MedAppTheme(darkTheme = false) { light = MaterialTheme.accents }
            MedAppTheme(darkTheme = true) { dark = MaterialTheme.accents }
        }
        return light to dark
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

    /**
     * Янтарный — цвет продукта, которому роли в Material нет: им говорится «занято или не
     * хватает», и `error` тут сказал бы неправду, потому что красным в приложении говорится
     * только о просрочке (PLAN H3). Выбирает между светлым и тёмным янтарём тема, поэтому она же
     * и проверяется: экран, взявший `LightAccents` напрямую, в тёмной теме светился бы.
     *
     * Красная проверка: отдать обеим схемам один и тот же [LightAccents] — тёмная краснеет.
     */
    @Test
    fun amberIsTheProductsOwnAccentInBothSchemes() {
        val (light, dark) = accents()

        assertEquals(MedAppAccents(Color(0xFF8A5300), Color(0xFFFFDDB3)), light)
        assertEquals(MedAppAccents(Color(0xFFFFB95C), Color(0xFF673F00)), dark)
    }
}
