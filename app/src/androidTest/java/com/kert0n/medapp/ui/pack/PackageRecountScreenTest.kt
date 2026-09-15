package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.presentation.pack.PackageRecountError
import com.kert0n.medapp.presentation.pack.PackageRecountPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageRecountUiState
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Пересчёт (PLAN H3 №9): одно поле, и человек называет то, что видит целиком, — так и написано
 * под полем. Ноль спрашивается словами, а не случается сам.
 */
@RunWith(AndroidJUnit4::class)
class PackageRecountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var submitted = 0
    private var edited: PackageRecountPresentationDTO? = null

    private fun show(state: PackageRecountUiState) {
        compose.setContent {
            MedAppTheme {
                PackageRecountScreen(
                    state = state,
                    onEdit = { edited = it },
                    onSubmit = { submitted++ },
                    onCancel = {}
                )
            }
        }
    }

    private fun recount(amount: String = "", error: PackageRecountError? = null) = PackageRecountUiState(
        pack = pack(id = PACK, name = "Нурофен").projected().toPresentationDTO(),
        form = PackageRecountPresentationDTO(amount),
        error = error
    )

    /** Пока коробка не прочитана — загрузка; нет её — сказано. */
    @Test
    fun theScreenWaitsAndSaysWhenTheBoxIsGone() {
        show(PackageRecountUiState())
        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
    }

    @Test
    fun aGoneBoxIsSaidOutLoud() {
        show(PackageRecountUiState(isGone = true))
        compose.onNodeWithText("Этой упаковки больше нет.").assertIsDisplayed()
        compose.onNodeWithText("Записать").assertDoesNotExist()
    }

    /** Записанное названо, а под полем сказано, что называют число целиком — не разницу. */
    @Test
    fun whatIsRecordedIsShownAndTheFieldExplainsItself() {
        show(recount())

        compose.onNodeWithText("Сейчас записано: 20 таблетка").assertIsDisplayed()
        compose.onNodeWithText("Назовите то, что видите целиком — вычитание сделает учёт.").assertIsDisplayed()
        compose.onNodeWithText("Выбросил", substring = true).assertDoesNotExist()
    }

    /** Напечатанное уходит в форму, «Записать» зовёт запись — кнопка не гаснет. */
    @Test
    fun typingEditsTheFormAndRecordCallsTheAction() {
        show(recount())

        compose.onNodeWithText("Пересчитал и увидел").performTextInput("17")
        compose.onNodeWithText("Записать").performClick()

        assertEquals(PackageRecountPresentationDTO("17"), edited)
        assertEquals(1, submitted)
    }

    /** Отказ виден словами и называет своё поле. */
    @Test
    fun aRefusalIsInWords() {
        show(recount(amount = "семнадцать", error = PackageRecountError.Amount(QuantityPresentationError.NOT_A_DECIMAL)))

        compose.onNodeWithText("Количество — это число: «20» или «0.5».").assertIsDisplayed()
    }

    /** Ноль — это «выбросить», и отказ отправляет на карточку, где это делается. */
    @Test
    fun zeroIsRefusedInWords() {
        show(recount(amount = "0", error = PackageRecountError.Zero))

        compose.onNodeWithText("Ноль — это выбросить упаковку: сделайте это с её карточки.").assertIsDisplayed()
        compose.onNodeWithText("Записать").assertIsDisplayed()
    }
}
