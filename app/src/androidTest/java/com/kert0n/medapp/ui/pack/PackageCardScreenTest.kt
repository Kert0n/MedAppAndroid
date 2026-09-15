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
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.pack.PackageCardUiState
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Карточка упаковки (PLAN H3 №6): человек открывает её, чтобы узнать, сколько есть и до какого
 * срока. Незаполненного она не показывает, а занятое и просроченное называет словами, не одним
 * цветом.
 */
@RunWith(AndroidJUnit4::class)
class PackageCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var edited = 0
    private var recounted = 0
    private var transferred = 0
    private var asked = 0
    private var confirmed = 0

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private fun box(
        expiresOn: String? = null,
        manufacturer: String? = null,
        hasUnconfirmedChanges: Boolean = false
    ): PackagePresentationDTO =
        pack(id = PACK, name = "Нурофен", expiresOn = expiresOn?.let(::expiry), manufacturer = manufacturer)
            .projected(hasUnconfirmedChanges = hasUnconfirmedChanges)
            .toPresentationDTO()

    /** Коробка, на которую заявили другие: оценка 20, доступно мне 14, свободно любому 9. */
    private fun claimedBox(): PackagePresentationDTO {
        val pkg = pack(id = PACK, name = "Нурофен", claims = Claims(BigDecimal("11"), BigDecimal("5")))
        return pkg.projection(
            availability = PackageAvailability(pkg, effective = tablets("20"), myAllocation = tablets("5")),
            hasUnconfirmedChanges = false,
            holdingCourseId = null,
            lastUsedAt = null
        ).toPresentationDTO()
    }

    private fun show(state: PackageCardUiState) {
        compose.setContent {
            MedAppTheme {
                PackageCardScreen(
                    state = state,
                    onEdit = { edited++ },
                    onRecount = { recounted++ },
                    onTransfer = { transferred++ },
                    onAskToRemove = { asked++ },
                    onConfirmRemoval = { confirmed++ },
                    onDismissRemoval = {},
                    onBack = {}
                )
            }
        }
    }

    private fun card(pack: PackagePresentationDTO = box(), medKitName: String? = "Домашняя", asksToRemove: Boolean = false) =
        PackageCardUiState(pack = pack, medKitName = medKitName, today = today, asksToRemove = asksToRemove)

    /** Пока база не ответила, карточка ждёт; коробки нет — сказано словами. */
    @Test
    fun theCardWaitsAndThenSaysWhenTheBoxIsGone() {
        show(PackageCardUiState())
        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Этой упаковки больше нет.").assertDoesNotExist()
    }

    @Test
    fun aGoneBoxIsSaidOutLoud() {
        show(PackageCardUiState(isGone = true))
        compose.onNodeWithText("Этой упаковки больше нет.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Выбросить").assertDoesNotExist()
    }

    /** Первым — сколько есть, крупно и в единице пачки. */
    @Test
    fun theCardLeadsWithHowMuchIsThere() {
        show(card())

        compose.onNodeWithText("Сколько есть").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
    }

    /**
     * Три числа по одному правилу: одинаковое не повторяется — три равных числа подряд читаются
     * как ошибка (PLAN D4). Чужая бронь названа значком и словами, а не одной заливкой.
     *
     * Красная проверка: показывать «доступно мне» всегда — на своей полке три одинаковых числа.
     */
    @Test
    fun whatIsNotDifferentIsNotRepeated() {
        show(card())

        compose.onNodeWithText("Доступно мне").assertDoesNotExist()
        compose.onNodeWithText("Свободно любому").assertDoesNotExist()
        compose.onNodeWithText("заявлены другими", substring = true).assertDoesNotExist()
    }

    @Test
    fun whatOthersClaimedIsNamedInWords() {
        show(card(pack = claimedBox()))

        compose.onNodeWithText("Доступно мне").assertIsDisplayed()
        compose.onNodeWithText("14 таблетка").assertIsDisplayed()
        compose.onNodeWithText("Свободно любому").assertIsDisplayed()
        compose.onNodeWithText("9 таблетка").assertIsDisplayed()
        compose.onNodeWithText("6 таблетка заявлены другими").assertIsDisplayed()
    }

    /** Пустых строк нет: «производитель: —» молчит. «Срок не указан», наоборот, — ответ. */
    @Test
    fun onlyWhatIsKnownIsShownButAnUnknownExpiryIsSaid() {
        show(card())

        compose.onNodeWithText("Что это").assertDoesNotExist()
        compose.onNodeWithText("Производитель").assertDoesNotExist()
        compose.onNodeWithText("Срок не указан").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatIsFilledInIsShown() {
        show(card(pack = box(manufacturer = "Reckitt", expiresOn = "2027-03-31")))

        compose.onNodeWithText("Что это").assertIsDisplayed()
        compose.onNodeWithText("Reckitt").assertIsDisplayed()
        compose.onNodeWithText("Годен до 03.2027").performScrollTo().assertIsDisplayed()
    }

    /** Просроченная говорит словами, а не одним цветом. */
    @Test
    fun anExpiredBoxSaysSoInWords() {
        show(card(pack = box(expiresOn = "2025-03-31")))

        compose.onNodeWithText("Просрочен 03.2025").performScrollTo().assertIsDisplayed()
    }

    /** Аптечка и лечение названы именами, а не тождествами; пометки коробки — словами. */
    @Test
    fun theShelfTheCourseAndTheMarksAreNamed() {
        show(
            card(pack = box(hasUnconfirmedChanges = true)).copy(
                holdingCourseTitle = "Нурофен, 7 дней",
                lastUsedOn = LocalDate.parse("2026-09-12")
            )
        )

        compose.onNodeWithText("Занято лечением «Нурофен, 7 дней»").assertIsDisplayed()
        compose.onNodeWithText("Изменение ещё не доехало до общей аптечки.").assertIsDisplayed()
        compose.onNodeWithText("12.09.2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Домашняя").performScrollTo().assertIsDisplayed()
    }

    /** Действия живут по местам: пересчёт у числа, перенос у места, правка — в панели. */
    @Test
    fun theActionsLiveWhereTheirSubjectIs() {
        show(card())

        compose.onNodeWithText("Пересчитать").performClick()
        compose.onNodeWithText("Перенести").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Править сведения").performClick()

        assertEquals(1, recounted)
        assertEquals(1, transferred)
        assertEquals(1, edited)
    }

    /**
     * «Выбросить» спрашивается **до** сценария: нажатие на значок только открывает разговор, и
     * лишь ответ в нём зовёт действие.
     *
     * Красная проверка: звать сценарий со значка — коробки не станет без вопроса.
     */
    @Test
    fun throwingAwayIsConfirmedBeforeTheScenario() {
        show(card())

        compose.onNodeWithContentDescription("Выбросить").performClick()

        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    @Test
    fun theConfirmationNamesWhatWillHappen() {
        show(card(asksToRemove = true))

        compose.onNodeWithText("Выбросить упаковку?").assertIsDisplayed()
        compose.onNodeWithText("Пачки больше не будет. Приёмы из неё останутся в истории.").assertIsDisplayed()
        compose.onNodeWithText("Выбросить").performClick()

        assertEquals(1, confirmed)
    }
}
