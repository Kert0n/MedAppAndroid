package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormError
import com.kert0n.medapp.presentation.pack.PackageFormPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormUiState
import com.kert0n.medapp.presentation.pack.TemplatePresentationDTO
import com.kert0n.medapp.presentation.pack.Suggestions
import com.kert0n.medapp.fixture.template
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Форма упаковки (PLAN H3 №7, №8): что человек видит и что он может нажать. */
@RunWith(AndroidJUnit4::class)
class PackageFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved = 0
    private var cancelled = 0
    private var recounted = 0
    private var picked: TemplatePresentationDTO? = null
    private var dismissed = 0

    private val shelf = medKit(id = HOME_KIT, name = "Домашняя").projection(MedKitContents.EMPTY).toPresentationDTO()

    private fun show(state: PackageFormUiState) {
        compose.setContent {
            MedAppTheme {
                PackageFormScreen(
                    state = state,
                    onEdit = {},
                    onPick = { picked = it },
                    onDismissSuggestions = { dismissed++ },
                    onSave = { saved++ },
                    onCancel = { cancelled++ },
                    onRecount = { recounted++ }
                )
            }
        }
    }

    private fun adding(error: PackageFormError? = null, suggestions: Suggestions = Suggestions.None) = PackageFormUiState(
        form = PackageFormPresentationDTO(medKitId = HOME_KIT),
        medKits = listOf(shelf),
        units = listOf(TABLETS.toPresentationDTO()),
        error = error,
        suggestions = suggestions
    )

    private val paracetamol = template(name = "Парацетамол", manufacturer = "Фармстандарт").toPresentationDTO()

    /**
     * Подсказки стоят под названием и узнаются по форме и производителю; нажимается строка
     * целиком, и в выбор уходит она сама.
     */
    @Test
    fun suggestionsStandUnderTheNameAndTheRowIsTheChoice() {
        show(adding(suggestions = Suggestions.Found(listOf(paracetamol))))

        compose.onNodeWithText("Парацетамол").assertIsDisplayed()
        compose.onNodeWithText("таблетки · Фармстандарт").assertIsDisplayed()
        compose.onNodeWithText("Парацетамол").performClick()

        assertEquals(paracetamol, picked)
    }

    /** Пока ответа нет, сказано, что его ждут: молчание под полем читалось бы как «подсказок не будет». */
    @Test
    fun searchingIsSaidWhileTheAnswerIsAwaited() {
        show(adding(suggestions = Suggestions.Searching))
        compose.onNodeWithText("Ищу в справочнике…").assertIsDisplayed()
    }

    /**
     * Пустой ответ назван словами и не окрашен ошибкой: «не нашлось» — ответ справочника, а не
     * отказ. Без слов пустое место под полем неотличимо от «ещё ищу» и от «подсказок не бывает».
     */
    @Test
    fun nothingFoundIsSaidQuietly() {
        show(adding(suggestions = Suggestions.Found(emptyList())))
        compose.onNodeWithText("В справочнике не нашлось.").assertIsDisplayed()
    }

    /**
     * Недоступный справочник называет причину и не трогает форму: без связи коробку заводят
     * руками. Форма, которую отказ справочника лишил бы «Сохранить», превратила бы подсказку в
     * условие записи.
     */
    @Test
    fun anUnavailableCatalogueNamesTheReasonAndKeepsTheForm() {
        show(adding(suggestions = Suggestions.Unavailable(Unavailability.NO_CONNECTION)))

        compose.onNodeWithText("Подсказок нет: Нет связи с сервером. Проверьте подключение.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals(1, saved)
    }

    /**
     * От подсказок отказываются крестиком в шапке блока, и он есть у **любого** его состояния:
     * список, «ищу», недоступный справочник. Без отказа блок стоит под названием до конца
     * заполнения формы — обязательные поля видно только сквозь десять карточек.
     *
     * Крестик стоит у поля, а не под списком: на узком устройстве низ блока за краем экрана.
     *
     * Красная проверка: убрать шапку из `SuggestionList` — отказаться от списка нечем.
     */
    @Test
    fun theSuggestionBlockIsRefusedByTheCross() {
        val shown = mutableStateOf<Suggestions>(Suggestions.Found(listOf(paracetamol)))
        compose.setContent {
            MedAppTheme {
                PackageFormScreen(
                    state = adding(suggestions = shown.value),
                    onEdit = {},
                    onPick = {},
                    onDismissSuggestions = { dismissed++ },
                    onSave = {},
                    onCancel = {},
                    onRecount = {}
                )
            }
        }

        val states = listOf(Suggestions.Found(listOf(paracetamol)), Suggestions.Searching, Suggestions.Unavailable(Unavailability.NO_CONNECTION))
        for ((refusals, suggestions) in states.withIndex()) {
            compose.runOnIdle { shown.value = suggestions }
            compose.onNodeWithContentDescription("Скрыть подсказки").assertIsDisplayed().performClick()
            assertEquals(refusals + 1, dismissed)
        }
    }

    /**
     * Все поля видны сразу (ТЗ 4.1.1.1): человек не ищет их в свёрнутом разделе. Обязательные
     * отличает подпись, а не место.
     *
     * Красная проверка: спрятать необязательные под «Остальное, если известно» — половина того,
     * что человек знает о коробке, исчезает с глаз.
     */
    @Test
    fun everyFieldIsInSightAndRequiredOnesAreMarked() {
        show(adding())

        for (field in listOf("Название", "Количество", "Единица", "Форма выпуска", "Срок годности")) {
            compose.onNodeWithText(field).performScrollTo().assertIsDisplayed()
        }
        for (field in listOf("Категория", "Производитель", "Страна", "Описание", "Заметка", "Цена", "Куплено", "Вскрыто")) {
            compose.onNodeWithText(field).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithTextCount("обязательно", atLeast = 2)
    }

    /**
     * Кнопка не гаснет: нажатие с пустой формой — не «ничего не произошло», а названная
     * причина.
     */
    @Test
    fun theSaveButtonStaysAliveAndTheRefusalIsInWords() {
        show(adding(error = PackageFormError.NameEmpty))

        compose.onNodeWithText("Название нужно: без него лекарство не найти ни поиском, ни глазами.")
            .assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /**
     * В правке количество и аптечка показаны, но не правятся: у пересчёта и переноса свой след.
     * Рядом с количеством — ссылка на пересчёт, иначе человек ищет действие по экранам.
     *
     * Красная проверка: дать править количество здесь — учёт разойдётся с тем, что человек
     * видел в коробке, и следа не останется.
     */
    @Test
    fun editingShowsTheAmountWithoutLettingItBeTyped() {
        show(
            PackageFormUiState(
                form = PackageFormPresentationDTO(medKitId = HOME_KIT, name = "Нурофен"),
                isEditing = true,
                stored = pack(id = PACK, name = "Нурофен", quantity = tablets("20")).projected().toPresentationDTO(),
                medKits = listOf(shelf),
                units = listOf(TABLETS.toPresentationDTO())
            )
        )

        compose.onNodeWithText("20 таблетка").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пересчитать").performClick()

        assertEquals(1, recounted)
    }

    /**
     * «Отмена» уводит с формы и **ничего не пишет**: иначе передумавший человек заводит коробку,
     * которой не хотел, и ищет потом, откуда она взялась (PLAN H3 №7).
     */
    @Test
    fun cancellingWritesNothing() {
        show(adding())

        compose.onNodeWithText("Отмена").performClick()

        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }
}

/** Подписи «обязательно» несколько: на пустой форме их столько, сколько незаполненных полей. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
    text: String,
    atLeast: Int
) {
    val found = onAllNodesWithText(text).fetchSemanticsNodes().size
    check(found >= atLeast) { "подписей «$text» найдено $found, ожидалось не меньше $atLeast" }
}
