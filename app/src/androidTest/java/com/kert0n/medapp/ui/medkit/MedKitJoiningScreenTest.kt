package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.presentation.medkit.MedKitJoiningRefusal
import com.kert0n.medapp.presentation.medkit.MedKitJoiningUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Присоединиться к чужой аптечке (PLAN H3 №22): что человек видит и что он может нажать. Что при
 * этом уходит на сервер, проверяет `MedKitJoiningViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class MedKitJoiningScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var typed = ""
    private var joined = 0
    private var scanned = 0

    private fun show(state: MedKitJoiningUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitJoiningScreen(
                    state,
                    onType = { typed = it },
                    onJoin = { joined++ },
                    onBack = {},
                    onScan = { scanned++ }
                )
            }
        }
    }

    /**
     * Код приглашения чаще показывают с экрана телефона, чем переписывают: камера стоит рядом с
     * полем и **до** вступления — сперва берут код, потом входят (U9).
     */
    @Test
    fun theCodeCanBeTakenByCamera() {
        show(MedKitJoiningUiState())

        compose.onNodeWithText("Отсканировать код").performClick()

        assertEquals(1, scanned)
    }

    /** Первым — поле кода: за этим сюда и приходят, и сказано, что человек получит. */
    @Test
    fun theCodeFieldComesFirstAndSaysWhatFollows() {
        show(MedKitJoiningUiState())

        compose.onNodeWithText("Присоединиться к аптечке").assertIsDisplayed()
        compose.onNodeWithText("Код приглашения").assertIsDisplayed()
        compose.onNodeWithText(
            "Попросите код у того, кто уже пользуется аптечкой. Вы увидите все её лекарства."
        ).assertIsDisplayed()
    }

    /**
     * Кнопка не гаснет: погашенная не объясняет, чего не хватает. Нажатие с пустым полем — не
     * «ничего не произошло», а названная причина.
     */
    @Test
    fun theButtonStaysAliveOnAnEmptyField() {
        show(MedKitJoiningUiState())

        compose.onNodeWithText("Присоединиться").performClick()

        assertEquals(1, joined)
    }

    /**
     * Негодное приглашение объясняется **одной фразой**: неизвестный ключ, истёкший и выход
     * пригласившего сервер не различает (PLAN B6), и гадать за него экран не станет.
     *
     * Красная проверка: три разных текста на один ответ сервера — приложение сочиняет то, чего не
     * знает, и человек чинит не то.
     */
    @Test
    fun aBadInvitationIsExplainedInOnePhrase() {
        show(MedKitJoiningUiState(code = "K7F", refusal = MedKitJoiningRefusal.Invalid))

        compose.onNodeWithText("Такого приглашения нет. Попросите новый код.").assertIsDisplayed()
    }

    /** «Уже в этой аптечке» — не беда: полка у человека есть, и сказано, где её искать. */
    @Test
    fun beingAlreadyInsideSaysWhereToLook() {
        show(MedKitJoiningUiState(code = "K7F", refusal = MedKitJoiningRefusal.AlreadyMember))

        compose.onNodeWithText("Вы уже в этой аптечке — она есть в списке.").assertIsDisplayed()
    }

    /** Связь оборвалась — причина названа теми же словами, что и на всех экранах. */
    @Test
    fun aLostAnswerIsSaidInTheCommonWords() {
        show(
            MedKitJoiningUiState(
                code = "K7F",
                refusal = MedKitJoiningRefusal.Unavailable(Unavailability.SERVER_SILENT)
            )
        )

        compose.onNodeWithText("Сервер не отвечает. Попробуйте позже.").assertIsDisplayed()
    }

    /** Набранное уходит наружу как есть: обрезает пробелы разбор, а не поле. */
    @Test
    fun whatIsTypedGoesOutAsItIs() {
        show(MedKitJoiningUiState())

        compose.onNodeWithText("Код приглашения").performTextInput(" K7F ")

        assertEquals(" K7F ", typed)
    }
}
