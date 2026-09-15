package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
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
 * Сквозной путь локального учёта без сети: аптечка → упаковка → пересчёт, и всё видно там, где
 * человек будет искать.
 *
 * Эта проверка отвечает не за поведение экрана — за **достижимость**: недостижимый экран и
 * функция без экрана это одна и та же ошибка (AGENTS). Она уже окупилась: экраны содержимого и
 * всех лекарств были написаны, проверены поодиночке — и не подключены к оболочке, а живой проход
 * упёрся в «этот экран ещё не готов».
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalRecordsJourneyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var vocabulary: VocabularyStorageRepository

    @Before
    fun setUp() {
        hilt.inject()
        // Словарь кладётся руками: его приносит первичная настройка, а здесь поднимается только
        // оболочка — без единиц форму упаковки заполнить нечем.
        runBlocking { vocabulary.save(listOf(TABLETS, MILLILITRES), listOf(TABLET_FORM)) }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @Test
    fun aShelfAPackageAndARecountWithoutNetwork() {
        // Аптечка заводится с пустого списка.
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Домашняя").fetchSemanticsNodes().isNotEmpty() }

        // Со списка аптечек — внутрь полки.
        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Здесь пока ничего нет.").assertIsDisplayed()

        // Оттуда — к заведению упаковки.
        compose.onNodeWithText("Завести упаковку").performClick()
        compose.onNodeWithText("Новая упаковка").assertIsDisplayed()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Количество").performTextInput("20")
        compose.onNodeWithText("Единица").performClick()
        compose.onNodeWithText("таблетка").performClick()
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        // Записанная коробка открывается карточкой.
        compose.waitUntil { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()

        // С карточки — на пересчёт, и он записывается.
        compose.onNodeWithContentDescription("Пересчитать").performClick()
        compose.onNodeWithText("Пересчитал и увидел").performTextInput("17")
        compose.onNodeWithText("Записать").performScrollTo().performClick()

        // Новое число видно там, где человек его будет искать, — на карточке.
        compose.waitUntil { compose.onAllNodesWithText("17 таблетка").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("17 таблетка").assertIsDisplayed()
    }

    /** Поиск со списка аптечек ведёт ко всем лекарствам, а не внутрь одной полки. */
    @Test
    fun theSearchFromTheShelvesLooksEverywhere() {
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Домашняя").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Найти лекарство во всех аптечках").performClick()

        compose.onNodeWithText("Все лекарства").assertIsDisplayed()
        compose.onNodeWithText("Во всех аптечках пока пусто.").assertIsDisplayed()
        // У всех лекарств хозяина нет: править и убирать там нечего.
        compose.onNodeWithContentDescription("Что можно с аптечкой").assertDoesNotExist()
    }
}
