package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозной путь без сети: с пустого списка человек заводит аптечку, кладёт в неё коробку,
 * пересчитывает — и всё видно там, где он будет искать.
 *
 * Эта проверка отвечает не за поведение экрана — за **достижимость**: недостижимый экран и
 * функция без экрана это одна и та же ошибка (AGENTS). В прошлый заход она окупилась сразу:
 * два экрана были написаны, проверены поодиночке — и не подключены к оболочке.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalRecordsJourneyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    @Before
    fun setUp() {
        hilt.inject()
        // Словарь приносит первый запуск с сервера; без сети его кладёт проверка.
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @Test
    fun aShelfIsCreatedFromTheEmptyListAndShowsUpThere() {
        compose.onNodeWithText("Завести аптечку").performClick()

        compose.onNodeWithText("Новая аптечка").assertIsDisplayed()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Место хранения (необязательно)").performTextInput("В ванной")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        // Записанное уводит с формы само: человек заводил полку, а не форму.
        compose.waitUntil {
            compose.onAllNodesWithText("Найти лекарство во всех аптечках").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("В ванной").assertIsDisplayed()
        compose.onNodeWithText("Пока пусто").assertIsDisplayed()
    }

    /**
     * Коробка заводится с полки, открывается карточкой и пересчитывается, и новое число видно на
     * карточке — там, где человек его и будет искать.
     *
     * Красная проверка: не записать пересчёт — карточка останется с прежним числом.
     */
    @Test
    fun aBoxIsAddedOpenedAndRecountedAndTheCardShowsTheNewNumber() {
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Пока пусто").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Завести упаковку").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Количество").performTextInput("20")
        compose.onNodeWithText("Единица").performScrollTo().performClick()
        compose.onNodeWithText("таблетка").performClick()
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        // Заведённая коробка открывается карточкой: человек заводил её, чтобы посмотреть.
        compose.waitUntil { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()

        compose.onNodeWithText("Пересчитать").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Сейчас записано: 20 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пересчитал и увидел").performTextInput("17")
        compose.onNodeWithText("Записать").performClick()

        compose.waitUntil { compose.onAllNodesWithText("17 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сколько есть").assertIsDisplayed()
    }
}
