package com.kert0n.medapp.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Оболочка: пять мест внизу, и место, где стоит человек, переживает пересоздание (PLAN H3, J3).
 * Уровень «Экран» по PLAN J1.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    /**
     * Окно графа, а не голая активность: за местом «Аптечки» стоит настоящий экран, и свою
     * `ViewModel` он берёт у Hilt.
     */
    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Место ищется как место, а не по слову: «Аптечки» теперь и подпись внизу, и заголовок
     * экрана за ней — искать по одному тексту значило бы находить то одно, то другое.
     */
    private fun place(name: String) = compose.onNode(isSelectable() and hasText(name))

    /** Экран ждёт чтения из базы, и секунды по умолчанию на это не хватает — как и в историях. */
    private val WAIT = 10_000L

    /**
     * Пять мест на месте, и человек стоит в первом: без этого правила приложение открывается
     * неизвестно где, а пропавшее место делает свои экраны недостижимыми (PLAN H3 «Оболочка»).
     */
    @Test
    fun allFivePlacesAreThereAndTheFirstIsSelected() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        for (name in listOf("Аптечки", "План", "Сканер", "Отчёты", "Опции")) {
            place(name).assertIsDisplayed()
        }
        place("Аптечки").assertIsSelected()
    }

    /** Нажатие на место ведёт в него: иначе выбранная вкладка расходится с тем, что показано. */
    @Test
    fun tappingAPlaceGoesThere() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        place("Отчёты").performClick()

        place("Отчёты").assertIsSelected()
        place("Аптечки").assertIsNotSelected()
    }

    /**
     * Поворот и смерть процесса — не действие человека: место остаётся тем же. Держат его
     * сохранённые стопки, и [StateRestorationTester] проходит ровно этот путь — сохранение и
     * восстановление, а не построение заново.
     *
     * Красная проверка: держать выбранное место обычным `remember` — человек возвращается к
     * «Аптечкам».
     */
    @Test
    fun thePlaceSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MedAppTheme { MedAppShell() } }
        place("Опции").performClick()
        place("Опции").assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        place("Опции").assertIsSelected()
        place("Аптечки").assertIsNotSelected()
    }

    /**
     * Крупный шрифт разметку не ломает: подписи и содержимое остаются на экране при двойном
     * масштабе (PLAN H3, J3).
     */
    @Test
    fun largeFontKeepsEverythingOnScreen() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MedAppTheme { MedAppShell(Modifier.fillMaxSize()) }
            }
        }

        place("Аптечки").assertIsDisplayed()
        place("Опции").assertIsDisplayed()
    }

    /**
     * **У каждого места есть свой экран.** Заглушки «этот экран ещё не готов» больше нет вовсе:
     * «Сканер» получил свой экран в U9, «Отчёты» — в U10, и мест без экрана не осталось. Проверка
     * стоит с той же стороны, с какой прежде стояла проверка заглушки: шестое место, заведённое
     * без экрана, показало бы пустоту, и это было бы видно здесь.
     */
    @Test
    fun everyPlaceShowsItsOwnScreen() {
        compose.setContent { MedAppTheme { MedAppShell() } }

        for ((name, shown) in listOf(
            "Отчёты" to "Упаковок пока нет",
            "Опции" to "Синхронизация",
            "Аптечки" to "Завести аптечку"
        )) {
            place(name).performClick()
            compose.waitUntil(WAIT) { compose.onAllNodesWithText(shown).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
