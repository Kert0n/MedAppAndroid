package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    /**
     * Ожидание с запасом: у сквозных проходов на одном устройстве набор тяжелее, чем у одиночной
     * проверки, и секунды по умолчанию не хватает — прогон краснел не о коде, а о занятости машины.
     */
    private val WAIT = 5_000L

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

    /**
     * С пустого места человек заводит первую полку и сразу видит её в списке: без этого пути
     * приложение после установки — тупик, из которого некуда нажать (PLAN H3 №2).
     */
    @Test
    fun aShelfIsCreatedFromTheEmptyListAndShowsUpThere() {
        compose.onNodeWithText("Завести аптечку").performClick()

        compose.onNodeWithText("Новая аптечка").assertIsDisplayed()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Место хранения (необязательно)").performTextInput("В ванной")
        compose.onNodeWithText("Сохранить").performClick()

        // Записанное уводит с формы само: человек заводил полку, а не форму.
        compose.waitUntil(WAIT) {
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
        aShelfWithABox()

        compose.onNodeWithText("Пересчитать").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сейчас записано: 20 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пересчитал и увидел").performTextInput("17")
        compose.onNodeWithText("Записать").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("17 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сколько есть").assertIsDisplayed()
    }

    /** Ноль в пересчёте не принимается: коробка на месте, отказ ведёт к «выбросить» на карточке. */
    @Test
    fun recountingToZeroIsRefusedAndTheBoxStays() {
        aShelfWithABox()

        compose.onNodeWithText("Пересчитать").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сейчас записано: 20 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пересчитал и увидел").performTextInput("0")
        compose.onNodeWithText("Записать").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Ноль — это выбросить упаковку: сделайте это с её карточки.").fetchSemanticsNodes().isNotEmpty() }
        // Экран остался пересчётом: ни ухода, ни вопроса — отказ и та же форма.
        compose.onNodeWithText("Записать").assertIsDisplayed()
        back()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("20 таблетка").fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * До каждого экрана набора можно дойти руками и вернуться: правка, пересчёт, перенос — с
     * карточки, и назад к ней; с карточки — на полку, с полки — к списку.
     */
    @Test
    fun everyScreenOfTheSetIsReachableAndLeadsBack() {
        aShelfWithABox()

        compose.onNodeWithText("Перенести").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Куда перенести").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Переносить некуда", substring = true).assertIsDisplayed()
        back()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Править сведения").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Правка упаковки").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }

        back()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Поиск по аптечке").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").assertIsDisplayed()

        back()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Найти лекарство во всех аптечках").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 упаковка").assertIsDisplayed()
    }

    /**
     * Подсказка справочника заполняет форму: напечатал «Нуро», выбрал «Нурофен» — название и
     * производитель встали сами, количество осталось за человеком.
     */
    @Test
    fun aSuggestionFillsTheFormWithWhatTheCardKnows() {
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Пока пусто").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Завести упаковку").performClick()

        compose.onNodeWithText("Название").performTextInput("Нуро")
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Нурофен").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").performClick()

        // На 360×640 dp поле производителя ниже экрана: до него докручивают.
        compose.onNodeWithText("Reckitt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Количество").performScrollTo().performTextInput("20")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reckitt").performScrollTo().assertIsDisplayed()
    }

    /** Полка «Домашняя» с коробкой «Нурофен, 20 таблетка», открытой карточкой. */
    private fun aShelfWithABox() {
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Пока пусто").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Завести упаковку").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Количество").performTextInput("20")
        compose.onNodeWithText("Единица").performScrollTo().performClick()
        compose.onNodeWithText("таблетка").performClick()
        compose.onNodeWithText("Сохранить").performClick()

        // Заведённая коробка открывается карточкой: человек заводил её, чтобы посмотреть.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
    }

    /** Возврат кнопкой: у каждого экрана набора она в панели. */
    private fun back() = compose.onNodeWithContentDescription("Назад").performClick()
}
