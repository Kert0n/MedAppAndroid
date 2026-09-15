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
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозной путь лечения без сети (PLAN U3): с пустого места «План» человек записывает
 * назначение, и оно видно там, где он будет искать. Проверка отвечает за **достижимость**
 * экранов набора: недостижимый экран и функция без экрана — одна и та же ошибка (AGENTS).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CoursesJourneyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
            // Полка заводится до коробок: без неё внешний ключ коробки не на что сослаться.
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
        compose.onNodeWithText("План").performClick()
    }

    /** Черновик с одним названием и заметкой сохраняется: «записал у врача, куплю завтра» (D5). */
    @Test
    fun aDraftWithOnlyANoteIsKeptAndListed() {
        compose.onNodeWithText("Записать лечение").performClick()

        compose.onNodeWithText("Новое лечение").assertIsDisplayed()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Заметка (необязательно)").performTextInput("по 2 после еды")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        // Записанное уводит с формы само: человек заводил лечение, а не форму.
        compose.waitUntil { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("по 2 после еды").assertIsDisplayed()
    }

    /**
     * Черновик ничего не занимает (PLAN D5): у него нет ни броней, ни назначений пачек. Коробка,
     * заведённая на полке, остаётся свободной — её держит не черновик, а начатое лечение.
     */
    @Test
    fun aDraftHoldsNoBoxAtAll() {
        runBlocking {
            database.packageRepository().add(
                pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM)
            )
        }
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Заметка (необязательно)").performTextInput("купить завтра")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Нурофен").performClick()
        compose.onNodeWithText("Источники").performScrollTo().performClick()

        // Стек пуст, и подключать пока нечего: доза и форма ещё не названы, сверять коробку не с чем.
        compose.onNodeWithText("Пачек пока нет — подключите первую.").assertIsDisplayed()
        compose.onNodeWithText("Подключить ещё").performClick()
        compose.onNodeWithText("Подходящих пачек нет: заведите коробку на полке или укажите ей форму.")
            .assertIsDisplayed()

        val held = runBlocking { database.packageRepository().projection(PACK)?.holdingCourseId }
        assertNull(held)
    }

    /** Черновик открывается редактором из списка и уходит по «удалить» — после вопроса. */
    @Test
    fun aDraftIsReopenedFromTheListAndDiscardedAfterAQuestion() {
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Нурофен").performClick()
        compose.onNodeWithText("Черновик лечения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Удалить черновик").performClick()
        compose.onNodeWithText("Удалить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Удалить черновик").performClick()

        compose.waitUntil { compose.onAllNodesWithText("Лечений пока нет.", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
