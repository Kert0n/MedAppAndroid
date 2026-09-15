package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormError
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
        error: PackageFormError? = null
    ) {
        val state = PackageFormUiState(
            form = form,
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
    @Test
    fun theFourRequiredFieldsAreVisibleAtOnceAndTheRestDoNotGetInTheWay() {
        show()

        for (field in listOf("Аптечка", "Название", "Количество", "Единица")) {
            compose.onNodeWithText(field).assertIsDisplayed()
        }
        compose.onNodeWithText("Остальное, если известно").assertIsDisplayed()
        compose.onNodeWithText("Производитель").assertDoesNotExist()
    }

    /** Раскрытый раздел показывает все поля до одного (ТЗ 4.1.1.1). */
    @Test
    fun theOptionalSectionHoldsEveryOtherField() {
        show()

        compose.onNodeWithText("Остальное, если известно").performClick()

        for (field in listOf("Форма выпуска", "Годен до", "Категория", "Производитель", "Страна")) {
            compose.onNodeWithText(field).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Отказ в необязательном поле раскрывает раздел: подсветить свёрнутое поле значит указать
     * человеку туда, куда он не смотрит.
     *
     * Красная проверка: не раскрывать раздел — поле с ошибкой остаётся невидимым.
     */
    @Test
    fun aRefusalInsideTheFoldedSectionOpensIt() {
        show(error = PackageFormError.TooLong(PackageFormError.Field.COUNTRY))

        compose.onNodeWithText("Страна").performScrollTo().assertIsDisplayed()
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
}
