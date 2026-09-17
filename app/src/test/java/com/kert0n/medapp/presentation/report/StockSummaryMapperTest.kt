package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import java.math.BigDecimal
import java.util.Currency
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сводка — в состояние экрана: доли полос, цены строками, «не указано» последним (PLAN H3). */
class StockSummaryMapperTest {

    private fun box(category: String? = null, form: DosageForm? = TABLET_FORM, price: Money? = null) =
        pack(id = Uuid.random(), category = category, form = form, price = price)

    /** Доля считается от всех живых пачек: полосы одной сводки складываются в целое. */
    @Test
    fun sharesAreCountedFromEveryLivingPackage() {
        val dto = StockSummary.of(
            listOf(box(category = "обезболивающее"), box(category = "обезболивающее"), box(category = "витамины"), box(category = "витамины"))
        ).toPresentationDTO()

        assertEquals(4, dto.packages)
        assertEquals(listOf(0.5f, 0.5f), dto.byCategory.map { it.share })
        assertEquals(1f, dto.byCategory.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    /** Порядок задаёт домен, и «не указано» приходит последним — маппер его не переставляет. */
    @Test
    fun theUnnamedGroupStaysLast() {
        val dto = StockSummary.of(
            listOf(box(category = "витамины"), box(category = null), box(category = null), box(category = null))
        ).toPresentationDTO()

        assertEquals(listOf("витамины", null), dto.byCategory.map { it.name })
        assertEquals(0.75f, dto.byCategory.last().share, 0.0001f)
    }

    /**
     * Форма называется именем словаря, а порядок при равном числе задаёт домен — по названию,
     * и «не указано» после всех.
     */
    @Test
    fun formsAreNamedByTheVocabulary() {
        val dto = StockSummary.of(listOf(box(form = TABLET_FORM), box(form = CAPSULE_FORM), box(form = null))).toPresentationDTO()

        assertEquals(listOf(CAPSULE_FORM.name, TABLET_FORM.name, null), dto.byForm.map { it.name })
        assertEquals(listOf(1f / 3, 1f / 3, 1f / 3), dto.byForm.map { it.share })
    }

    /** Валюты не складываются: две валюты — две строки, и числа нормализованы. */
    @Test
    fun everyCurrencyGetsItsOwnRow() {
        val euro = Currency.getInstance("EUR")
        val dto = StockSummary.of(
            listOf(box(price = Money(BigDecimal("120.50"))), box(price = Money(BigDecimal("79.50"))), box(price = Money(BigDecimal("3.00"), euro)))
        ).toPresentationDTO()

        assertEquals(listOf(MoneyPresentationDTO("3", "EUR"), MoneyPresentationDTO("200", "RUB")), dto.prices)
        assertEquals(0, dto.unpriced)
    }

    /** Пачки без цены — своим числом: «неизвестно» не превращается в ноль. */
    @Test
    fun packagesWithoutAPriceAreCountedApartFromMoney() {
        val dto = StockSummary.of(listOf(box(price = Money(BigDecimal("50"))), box(), box())).toPresentationDTO()

        assertEquals(listOf(MoneyPresentationDTO("50", "RUB")), dto.prices)
        assertEquals(2, dto.unpriced)
    }

    /** Пустая сводка — пустая, а не нули: полос нет вовсе, и делить на ноль нечего. */
    @Test
    fun anEmptyStockGivesNoBarsAtAll() {
        val dto = StockSummary.of(emptyList()).toPresentationDTO()

        assertTrue(dto.isEmpty)
        assertEquals(emptyList<ReportBarPresentationDTO>(), dto.byCategory)
        assertEquals(emptyList<ReportBarPresentationDTO>(), dto.byForm)
        assertEquals(0, dto.unpriced)
    }
}
