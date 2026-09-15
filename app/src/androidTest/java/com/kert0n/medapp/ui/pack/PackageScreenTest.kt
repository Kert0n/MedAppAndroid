package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.pack.PackageUiState
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Карточка упаковки (PLAN H3 №6): что человек видит первым и чего он не видит вовсе.
 */
@RunWith(AndroidJUnit4::class)
class PackageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var removalAsked = 0
    private var removed = 0

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private fun show(state: PackageUiState) {
        compose.setContent {
            MedAppTheme {
                PackageScreen(
                    state = state,
                    onBack = {},
                    onEdit = {},
                    onChangeAmount = {},
                    onTransfer = {},
                    onAskToRemove = { removalAsked++ },
                    onConfirmRemoval = { removed++ },
                    onDismissRemoval = {}
                )
            }
        }
    }

    private fun card(
        quantity: String = "20",
        claims: Claims? = null,
        myAllocation: String = "0",
        expiresOn: com.kert0n.medapp.domain.pack.ExpiryDate? = null,
        holdingCourseTitle: String? = null
    ): PackageUiState {
        val pkg = pack(quantity = tablets(quantity), claims = claims, expiresOn = expiresOn)
        return PackageUiState(
            pack = pkg.projection(
                availability = PackageAvailability(pkg, tablets(quantity), tablets(myAllocation)),
                hasUnconfirmedChanges = false,
                holdingCourseId = null,
                lastUsedAt = null
            ).toPresentationDTO(),
            holdingCourseTitle = holdingCourseTitle,
            medKitName = "Домашняя",
            today = today
        )
    }

    /** Первым — сколько есть: ради этого карточку и открывают. */
    @Test
    fun howMuchIsThereComesFirst() {
        show(card())

        compose.onNodeWithText("Сколько есть").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
    }

    /**
     * Одинаковое не повторяется: три равных числа подряд человек читает как ошибку (PLAN D4).
     *
     * Красная проверка: показывать «доступно мне» и «свободно любому» всегда — на своей полке без
     * броней появятся три одинаковых числа.
     */
    @Test
    fun theSameNumberIsNotRepeatedThreeTimes() {
        show(card())

        compose.onNodeWithText("Доступно мне").assertDoesNotExist()
        compose.onNodeWithText("Свободно любому").assertDoesNotExist()
    }

    /** Чужая бронь названа словами и значком, а не одной заливкой. */
    @Test
    fun aClaimByOthersIsSpelledOut() {
        show(card(claims = Claims(total = BigDecimal("6"), mine = BigDecimal.ZERO)))

        compose.onNodeWithText("6 таблетка заявлены другими").assertIsDisplayed()
        compose.onNodeWithContentDescription("Часть заявлена другими участниками").assertIsDisplayed()
        compose.onNodeWithText("Доступно мне").assertIsDisplayed()
    }

    /** Держащее лечение названо, а не показано тождеством. */
    @Test
    fun theHoldingCourseIsNamed() {
        show(card(holdingCourseTitle = "Нурофен, 7 дней"))

        compose.onNodeWithText("Занято лечением «Нурофен, 7 дней»").assertIsDisplayed()
    }

    /** Просроченная говорит словами; неизвестный срок сказан вслух, а не пропущен. */
    @Test
    fun expiryIsAlwaysSaidOutLoud() {
        show(card(expiresOn = expiry("2025-03-31")))

        compose.onNodeWithText("Просрочен 03.2025").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnknownExpiryIsAnAnswerNotASilence() {
        show(card())

        compose.onNodeWithText("Срок не указан").performScrollTo().assertIsDisplayed()
    }

    /** Пустых строк нет: о чём сказать нечего, о том карточка молчит целиком. */
    @Test
    fun thereAreNoEmptyLines() {
        show(card())

        compose.onNodeWithText("Что это").assertDoesNotExist()
        compose.onNodeWithText("Производитель").assertDoesNotExist()
    }

    /** Названа аптечка, а не её тождество. */
    @Test
    fun theShelfIsNamed() {
        show(card())

        compose.onNodeWithText("Домашняя").performScrollTo().assertIsDisplayed()
    }

    /**
     * Удаление необратимо и потому спрашивается: нажатие открывает разговор, а не удаляет.
     *
     * Красная проверка: звать сценарий прямо из кнопки — коробка исчезает без вопроса.
     */
    @Test
    fun removalAsksBeforeItRemoves() {
        show(card())

        compose.onNodeWithText("Удалить").performScrollTo().performClick()

        assertEquals(1, removalAsked)
        assertEquals(0, removed)
    }

    @Test
    fun theRemovalDialogSaysWhatWillBeLost() {
        show(card().copy(asksToRemove = true))

        compose.onNodeWithText("Удалить упаковку?").assertIsDisplayed()
        compose.onNodeWithText(
            "Коробка исчезнет вместе со своими сведениями. Приёмы из неё останутся в истории."
        ).assertIsDisplayed()
    }

    /** Коробки не стало, пока карточка была открыта: сказано словами, а не пустым экраном. */
    @Test
    fun aPackageThatIsGoneIsSaidOutLoud() {
        show(PackageUiState(isGone = true))

        compose.onNodeWithText("Этой упаковки больше нет.").assertIsDisplayed()
    }
}
