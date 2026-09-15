package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormError
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormUiState
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Форма упаковки (PLAN H3 №7): что человек видит и что он может нажать. Что при этом
 * записывается, проверяет `PackageFormViewModelTest` — экран об этом не знает вовсе.
 */
@RunWith(AndroidJUnit4::class)
class PackageFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val edited = mutableListOf<PackageFormPresentationDTO>()
    private var saved = 0
    private var cancelled = 0

    private fun show(
        form: PackageFormPresentationDTO = PackageFormPresentationDTO(medKitId = HOME_KIT),
        error: PackageFormError? = null,
        editing: PackagePresentationDTO? = null
    ) {
        val state = PackageFormUiState(
            form = form,
            isEditing = editing != null,
            stored = editing,
            medKits = listOf(medKit(id = HOME_KIT, name = "Домашняя").projection(MedKitContents.EMPTY).toPresentationDTO()),
            units = listOf(TABLETS.toPresentationDTO()),
            error = error
        )
        compose.setContent {
            MedAppTheme {
                PackageFormScreen(
                    state = state,
                    onEdit = { edited += it },
                    onSave = { saved++ },
                    onCancel = { cancelled++ }
                )
            }
        }
    }

    /**
     * Четыре обязательных поля видны сразу, а остальные есть, но не мешают: человек заводит
     * коробку, зная о ней четыре вещи (PLAN C1).
     */
    /**
     * Видны сразу **все** поля: прятать необязательные в свёрнутый раздел значило бы прятать
     * половину того, что человек про коробку знает (ТЗ 4.1.1.1).
     *
     * Красная проверка: свернуть остальные поля — «Производитель» со «Страной» исчезнут.
     */
    @Test
    fun everyFieldIsVisibleAtOnce() {
        show()

        for (field in listOf("Аптечка", "Название", "Количество", "Единица")) {
            compose.onNodeWithText(field).assertIsDisplayed()
        }
        for (field in listOf("Форма выпуска", "Годен до", "Категория", "Производитель", "Страна")) {
            compose.onNodeWithText(field).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Главные поля отличает не место, а подпись «обязательно» — и она исчезает, как только поле
     * заполнено.
     */
    @Test
    fun theRequiredFieldsSaySoWhileTheyAreEmpty() {
        show()

        // Аптечка подставлена той, из которой человек пришёл, и о себе не просит: просят
        // остальные три — название, количество и единица.
        compose.onAllNodesWithText("Обязательно").assertCountEquals(3)
    }

    /** Пришли не из аптечки — просит и она: без неё коробка нигде не лежит. */
    @Test
    fun withoutAShelfItAsksForOneToo() {
        show(form = PackageFormPresentationDTO())

        compose.onAllNodesWithText("Обязательно").assertCountEquals(4)
    }

    @Test
    fun aFilledRequiredFieldStopsAskingForItself() {
        show(form = PackageFormPresentationDTO(medKitId = HOME_KIT, name = "Нурофен"))

        compose.onAllNodesWithText("Обязательно").assertCountEquals(2)
    }

    /** Кнопка не гаснет: погашенная не объясняет, чего не хватает. */
    @Test
    fun theSaveButtonDoesNotGoDarkOnAnEmptyForm() {
        show()

        compose.onNodeWithText("Сохранить").performScrollTo().performClick()

        assertEquals(1, saved)
    }

    /** Отказ назван словами. */
    @Test
    fun aRefusalIsSpelledOut() {
        show(error = PackageFormError.AmountIsZero)

        compose.onNodeWithText("Пустую упаковку заводить незачем — укажите, сколько в ней есть.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun typingGoesBackToTheForm() {
        show()

        compose.onNodeWithText("Название").performTextInput("Нурофен")

        assertEquals("Нурофен", edited.single().name)
    }

    /** Брошенная форма не пишет ничего. */
    @Test
    fun anAbandonedFormWritesNothing() {
        show(form = PackageFormPresentationDTO(medKitId = HOME_KIT, name = "Нурофен"))

        compose.onNodeWithText("Отмена").performScrollTo().performClick()

        assertEquals(1, cancelled)
        assertEquals(0, saved)
    }

    /**
     * В правке количество показано, но не правится: его двигают пересчёт и утилизация, у которых
     * свой след. Куда за ними идти, сказано тут же.
     *
     * Красная проверка: оставить поле количества правимым — появится поле ввода, и человек
     * изменит число там, где следа не остаётся.
     */
    @Test
    fun editingShowsTheAmountButDoesNotLetItBeChanged() {
        val stored = pack(id = PACK, name = "Нурофен", quantity = tablets("20")).projected().toPresentationDTO()
        show(
            form = PackageFormPresentationDTO(medKitId = HOME_KIT, name = "Нурофен"),
            editing = stored
        )

        compose.onNodeWithText("Правка упаковки").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
        compose.onNodeWithText("Изменить").assertIsDisplayed()
        compose.onNodeWithText("Единица").assertDoesNotExist()
    }

    /** Форму общей коробки не стереть, и экран объясняет это, а не молчит (PLAN D3). */
    @Test
    fun clearingTheFormOfASharedBoxIsExplained() {
        show(error = PackageFormError.FormClearUnsupported)

        compose.onNodeWithText("У общей упаковки форму выпуска нельзя стереть — только заменить другой.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
