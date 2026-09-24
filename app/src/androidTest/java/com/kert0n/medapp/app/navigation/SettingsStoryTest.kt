package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.feature.settings.AppLanguage
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.fixture.TestLanguages
import com.kert0n.medapp.fixture.TestPermissions
import com.kert0n.medapp.fixture.pressAfterTyping
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Зои — телефон по-английски, таблетки по-русски** (`docs/истории.md`, U11).
 *
 * Зоя проходит «Опции» одним вечером: пороги уведомлений, разрешение, которое она чинит в
 * настройках телефона, и язык. Уход в настройки телефона — это уход окна со сцены и возвращение
 * на неё: разрешение меняется «там», а экран узнаёт об этом, вернувшись.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var settings: SettingsStore

    private val WAIT = 10_000L

    @Before
    fun setUp() {
        TestPermissions.notifications = false
        hilt.inject()
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @After
    fun tearDown() {
        TestPermissions.reset()
        TestLanguages.reset()
    }

    /** Путь Зои целиком: записанное читается снова, починенное разрешение гаснет само, выбор языка помнится. */
    @Test
    fun zoyaSetsUpTheAppInOneEvening() {
        toOptions()

        // Пороги: за пять дней о нехватке, откладывать на двадцать минут.
        compose.onNodeWithText("Уведомления").performClick()
        see("Предупреждать о нехватке за, дней")
        compose.onNodeWithText("Предупреждать о нехватке за, дней").performTextReplacement("5")
        compose.onNodeWithText("Отложить на, мин").performTextReplacement("20")
        compose.pressAfterTyping("Сохранить")
        see("Опции")
        val saved = runBlocking { settings.current() }
        assertEquals(5L, saved.notifications.coverageThresholdDays)
        assertEquals(20, saved.notifications.snoozeMinutes)

        // Разрешение: выключено — ушла в настройки телефона, включила, вернулась.
        see("Что-то выключено")
        compose.onNodeWithText("Разрешения").performClick()
        see("Выключено — исправить в настройках телефона")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        TestPermissions.notifications = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(WAIT) { !shown("Выключено — исправить в настройках телефона") }
        compose.onAllNodesWithText("Разрешено").onLast().assertIsDisplayed()
        back()

        // Язык: «Русский», и выбор помнится.
        compose.onNodeWithText("Язык").performClick()
        see("Как в системе")
        compose.onNodeWithText("Русский").performClick()
        assertEquals(AppLanguage.RUSSIAN, TestLanguages.current())
        back()
        compose.onNodeWithText("Язык").performClick()
        see("Русский")
        compose.onNodeWithText("Русский").assertIsSelected()
        back()

        // Форма читает записанное, а не умолчания.
        compose.onNodeWithText("Уведомления").performClick()
        see("Предупреждать о нехватке за, дней")
        compose.onNode(hasText("5") and hasText("Предупреждать о нехватке за, дней")).assertIsDisplayed()
        compose.onNode(hasText("20") and hasText("Отложить на, мин")).assertIsDisplayed()
    }

    private fun toOptions() {
        see("Опции")
        compose.onAllNodesWithText("Опции").onLast().performClick()
        see("Уведомления")
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun see(text: String) = compose.waitUntil(WAIT) { shown(text) }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
