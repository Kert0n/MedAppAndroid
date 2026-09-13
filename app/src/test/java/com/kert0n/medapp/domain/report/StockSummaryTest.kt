package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import java.math.BigDecimal
import java.util.Currency
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сводка считает живые пачки, а не догадки: по категориям и формам, цена — по валютам, пачки без
 * цены — отдельным числом (ТЗ 4.1.1.10.3; PLAN H6).
 */
class StockSummaryTest {

    private fun box(category: String? = null, form: com.kert0n.medapp.domain.value.DosageForm? = TABLET_FORM, price: Money? = null) =
        pack(id = Uuid.random(), category = category, form = form, price = price)

    private fun rub(amount: String) = Money(BigDecimal(amount))

    @Test
    fun packagesAreCountedByCategoryAndForm() {
        val summary = StockSummary.of(
            listOf(
                box(category = "обезболивающее"), box(category = "обезболивающее", form = CAPSULE_FORM),
                box(category = "витамины"), box(category = null, form = null)
            )
        )

        assertEquals(4, summary.packages)
        assertEquals(
            listOf(
                StockSummary.CategoryCount("обезболивающее", 2),
                StockSummary.CategoryCount("витамины", 1),
                StockSummary.CategoryCount(null, 1)
            ),
            summary.byCategory
        )
        assertEquals(
            listOf(StockSummary.FormCount(TABLET_FORM, 2), StockSummary.FormCount(CAPSULE_FORM, 1), StockSummary.FormCount(null, 1)),
            summary.byForm
        )
    }

    /**
     * Три пачки без цены — число три, а не ноль рублей; две валюты — две суммы.
     *
     * Красная проверка: сложить цены без валюты — рубли и евро смешаются в одно число.
     */
    @Test
    fun pricesStayInTheirCurrenciesAndUnpricedIsCountedApart() {
        val euro = Currency.getInstance("EUR")
        val summary = StockSummary.of(
            listOf(
                box(price = rub("120.50")), box(price = rub("79.50")), box(price = Money(BigDecimal("3"), euro)),
                box(), box(), box()
            )
        )

        assertEquals(listOf(Money(BigDecimal("3"), euro), rub("200")), summary.prices)
        assertEquals(3, summary.unpriced)
    }

    /** Коробка, которую решили выбросить, — уже не то, что у человека есть. Единицы не мешают. */
    @Test
    fun aBoxBeingRemovedIsNotCountedAndUnitsDoNotMatter() {
        val removing = pack(id = Uuid.random()).markRemoving(by = Uuid.random())
        val summary = StockSummary.of(listOf(box(), pack(id = Uuid.random(), quantity = millilitres("100")), removing))

        assertEquals(2, summary.packages)
    }

    @Test
    fun noPackagesIsAnEmptySummary() {
        assertEquals(StockSummary(0, emptyList(), emptyList(), emptyList(), 0), StockSummary.of(emptyList()))
    }
}
