package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.pack.AmountChange
import com.kert0n.medapp.presentation.pack.PackageAmountError
import com.kert0n.medapp.presentation.pack.PackageAmountPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageAmountUiState
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Пересчёт и утилизация (PLAN H3 №9): что человек видит и о чём его спрашивают.
 */
@RunWith(AndroidJUnit4::class)
class PackageAmountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val edited = mutableListOf<PackageAmountPresentationDTO>()
    private var submitted = 0
    private var confirmed = 0

    private fun show(
        change: AmountChange = AmountChange.RECOUNT,
        amount: String = "",
        error: PackageAmountError? = null,
        asksToEmpty: Boolean = false
    ) {
        val state = PackageAmountUiState(
            pack = pack(id = PACK, quantity = tablets("20")).projected().toPresentationDTO(),
            form = PackageAmountPresentationDTO(change, amount),
            error = error,
            asksToEmpty = asksToEmpty
        )
        compose.setContent {
            MedAppTheme {
                PackageAmountScreen(
                    state = state,
                    onEdit = { edited += it },
                    onSubmit = { submitted++ },
                    onCancel = {},
                    onConfirmEmptying = { confirmed++ },
                    onDismissEmptying = {}
                )
            }
        }
    }

    /** Две вкладки, а не два экрана: наблюдение одно, объяснения два. */
    @Test
    fun oneObservationWithTwoExplanations() {
        show()

        compose.onNodeWithText("Пересчитал").assertIsDisplayed()
        compose.onNodeWithText("Выбросил").assertIsDisplayed()
    }

    /** Видно, от чего человек отсчитывает. */
    @Test
    fun whatIsWrittenNowIsInSight() {
        show()

        compose.onNodeWithText("Сейчас записано: 20 таблетка").assertIsDisplayed()
    }

    /**
     * Чем пересчёт отличается от утилизации, сказано словами: числа у них выглядят одинаково, и
     * перепутать их стоит коробки.
     *
     * Красная проверка: убрать пояснения — на обеих вкладках останется одно поле «число».
     */
    @Test
    fun theDifferenceBetweenTheTwoIsSpelledOut() {
        show()

        compose.onNodeWithText("Назовите то, что видите целиком, — вычитание сделает учёт.")
            .assertIsDisplayed()
    }

    @Test
    fun theDisposalTabSaysWhatItCounts() {
        show(change = AmountChange.DISPOSAL)

        compose.onNodeWithText("Сколько выбросил").assertIsDisplayed()
        compose.onNodeWithText("Столько ушло; остальное остаётся в коробке.").assertIsDisplayed()
    }

    /** Ни причины, ни заметки здесь нет: отчёт об утилизации — только число (PLAN C1). */
    @Test
    fun aDisposalAsksForANumberAndNothingElse() {
        show(change = AmountChange.DISPOSAL)

        compose.onNodeWithText("Причина").assertDoesNotExist()
        compose.onNodeWithText("Заметка").assertDoesNotExist()
    }

    /** Ноль спрашивается словами, а не исчезновением коробки. */
    @Test
    fun goingToZeroIsAConversation() {
        show(asksToEmpty = true)

        compose.onNodeWithText("Коробки больше не будет?").assertIsDisplayed()
        compose.onNodeWithText(
            "В ней не останется ничего, и она исчезнет. Приёмы из неё останутся в истории."
        ).assertIsDisplayed()

        compose.onNodeWithText("Да, коробка кончилась").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun aRefusalIsSpelledOut() {
        show(change = AmountChange.DISPOSAL, error = PackageAmountError.MoreThanThereIs)

        compose.onNodeWithText("Столько в коробке и не лежало.").assertIsDisplayed()
    }

    @Test
    fun recordingGoesBackToTheViewModel() {
        show(amount = "17")

        compose.onNodeWithText("Записать").performClick()

        assertEquals(1, submitted)
    }
}
