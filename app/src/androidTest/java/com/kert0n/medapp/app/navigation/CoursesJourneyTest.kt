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
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
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

    /**
     * Ожидание с запасом: у сквозных проходов на одном устройстве набор тяжелее, чем у одиночной
     * проверки, и секунды по умолчанию не хватает — прогон краснел не о коде, а о занятости машины.
     */
    private val WAIT = 5_000L

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
        compose.onNodeWithText("Сохранить").performClick()

        // Записанное уводит с формы само: человек заводил лечение, а не форму.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Нурофен").performClick()
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()

        // Стек пуст, и подключать пока нечего: доза и форма ещё не названы, сверять коробку не с чем.
        compose.onNodeWithText("Пачек пока нет — подключите первую.").assertIsDisplayed()
        compose.onNodeWithText("Подключить ещё").performClick()
        // Коробка на полке есть, но подключать её не к чему: доза и форма лечения не названы.
        compose.onNodeWithText("Сначала укажите дозу и форму лечения.").assertIsDisplayed()

        val held = runBlocking { database.packageRepository().projection(PACK)?.holdingCourseId }
        assertNull(held)
    }

    /**
     * Лечение начинается и без лекарства на руках, а карточка сразу говорит, что оно не
     * обеспечено: пачка подключается, когда её купят (PLAN D5, U3).
     */
    @Test
    fun aCourseWithoutABoxStartsAndSaysItIsNotCovered() {
        val scenarios = Scenarios(database, Instant.now())
        runBlocking {
            val created = scenarios.courseDrafting.create("Нурофен")
            scenarios.courseDrafting.edit(
                created.id, created.revision,
                listOf(
                    CourseDrafting.Edit.SetDose(dose("2")),
                    CourseDrafting.Edit.SetForm(TABLET_FORM),
                    CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.now(MOSCOW))),
                    CourseDrafting.Edit.SetTotalDoses(Doses(4))
                )
            )
        }

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").performClick()
        compose.onNodeWithText("Начать лечение").performClick()

        // Начатое ведёт на карточку, и первое, что там сказано, — чем лечение обеспечено.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Не хватает 4 приёмов", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("нужно 4 приёма · обеспечено 0").assertIsDisplayed()
    }

    /** Черновик открывается редактором из списка и уходит по «удалить» — после вопроса. */
    @Test
    fun aDraftIsReopenedFromTheListAndDiscardedAfterAQuestion() {
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Нурофен").performClick()
        compose.onNodeWithText("Черновик лечения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Удалить черновик").performClick()
        compose.onNodeWithText("Удалить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Удалить черновик").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Лечений пока нет.", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Чтобы подключить источники, новый черновик приходится записать — номер нужен коробкам.
     * Сохранить его никто не просил, поэтому уход с формы спрашивает, и «Удалить» его уносит:
     * человек заводил лечение, а не строку в списке (PLAN H3 №15).
     */
    @Test
    fun aDraftWrittenOnlyForItsSourcesIsOfferedForDeletionOnTheWayOut() {
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()

        // Источники открылись — значит, черновик записан.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Подключить ещё").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Назад").performClick()

        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Оставить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Удалить").performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Лечений пока нет.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
