package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var taken = 0
    private var recounted = 0
    private var transferred = 0
    private var asked = 0
    private var confirmed = 0

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private fun box(
        expiresOn: String? = null,
        manufacturer: String? = null,
        description: String? = null,
        hasUnconfirmedChanges: Boolean = false
    ): PackagePresentationDTO =
        pack(
            id = PACK, name = "Нурофен", expiresOn = expiresOn?.let(::expiry),
            manufacturer = manufacturer, description = description
        )
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
                    onTake = { taken++ },
                    onHistory = {},
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
        compose.onNodeWithText("Этого лекарства больше нет.").assertDoesNotExist()
    }

    /**
     * Коробка в базе есть, но сервер ещё отвечает: карточка ждёт, и ни одно действие не нажать —
     * иначе приняли бы или выбросили по числу, которое через миг сменится (PLAN E4).
     */
    @Test
    fun whileTheServerAnswersTheCardWaitsWithNothingToPress() {
        show(card().copy(isFreshening = true))

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Сколько есть").assertDoesNotExist()
        compose.onNodeWithContentDescription("Выбросить").assertDoesNotExist()
        compose.onNodeWithText("Принять").assertDoesNotExist()
    }

    @Test
    fun aGoneBoxIsSaidOutLoud() {
        show(PackageCardUiState(isGone = true))
        compose.onNodeWithText("Этого лекарства больше нет.").assertIsDisplayed()
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

        // «Что это» теперь последнее: на узком экране до него долистывают, и это нормально —
        // справочное не должно занимать первый экран (решение владельца 2026-09-17).
        compose.onNodeWithText("Годен до 03.2027").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Что это").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reckitt").performScrollTo().assertIsDisplayed()
    }

    /** Цена со знакомой валютой — знаком, с незнакомым кодом — самим кодом, а не падением. */
    @Test
    fun aPriceIsShownWithItsCurrencySign() {
        val priced = box().copy(price = MoneyPresentationDTO("320", "RUB"))
        show(card(pack = priced))
        compose.onNodeWithText("320 ₽").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnknownCurrencyCodeIsShownAsItself() {
        show(card(pack = box().copy(price = MoneyPresentationDTO("320", "ZZ"))))
        compose.onNodeWithText("320 ZZ").performScrollTo().assertIsDisplayed()
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

        compose.onNodeWithText("Выбросить лекарство?").assertIsDisplayed()
        compose.onNodeWithText("Лекарства больше не будет. Приёмы из него останутся в истории.").assertIsDisplayed()
        compose.onNodeWithText("Выбросить").performClick()

        assertEquals(1, confirmed)
    }

    /**
     * «Принять» — самое частое действие с коробкой, и стоит оно плавающей кнопкой: в ряду с
     * «Пересчитать» они делили ширину, и слово переносилось по слогам (замечание владельца).
     */
    @Test
    fun takingIsTheFloatingActionOfTheCard() {
        show(card())

        // Слово у плавающей кнопки живёт подписью значка: внутрь своего узла она текста не пускает.
        compose.onNodeWithContentDescription("Принять").performClick()

        assertEquals(1, taken)
    }

    /**
     * Первым — сколько есть, потом срок, потом место, и лишь затем что это за лекарство. Описание
     * из справочника занимает целый экран: стоя вторым, оно отодвигало срок и место за край, и
     * человек листал инструкцию, чтобы узнать, куда идти (замечание владельца 2026-09-17).
     */
    @Test
    fun theUrgentComesBeforeTheReference() {
        show(card(box(manufacturer = "Реккитт")))

        val howMuch = compose.onNodeWithText("Сколько есть").getUnclippedBoundsInRoot()
        val dates = compose.onNodeWithText("Сроки и цена").getUnclippedBoundsInRoot()
        val where = compose.onNodeWithText("Где лежит").getUnclippedBoundsInRoot()
        val what = compose.onNodeWithText("Что это").getUnclippedBoundsInRoot()

        assertTrue("сроки идут после остатка", dates.top > howMuch.top)
        assertTrue("место идёт после сроков", where.top > dates.top)
        assertTrue("описание идёт последним", what.top > where.top)
    }


    /**
     * Отказ сервера о числе виден там, где о числе и говорят, и ведёт туда, чем он лечится:
     * спор в том, сколько в коробке на самом деле (PLAN E3, REQ-045). Пересчёт начинается с
     * того числа, которое принёс снимок, — экран не придумывает своего.
     *
     * Красная проверка: промолчать об отказе — человек видит серверное число и не понимает, куда
     * делась его правка.
     */
    @Test
    fun aServerRefusalAboutTheNumberLeadsToRecounting() {
        show(card().copy(isRefusedByServer = true))

        compose.onNodeWithText("Сервер отклонил изменение").assertIsDisplayed()
        compose.onNodeWithText("Пересчитайте остаток — спор о том, сколько здесь на самом деле").performClick()

        assertEquals(1, recounted)
    }
}
