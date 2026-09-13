package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Money
import java.math.BigDecimal

/**
 * Что у меня есть сейчас (ТЗ 4.1.1.10.3; PLAN H6) — единственный отчёт не о расходе: живые
 * пачки всех доступных мне полок, включая общие. Коробка, о которой уже решено «выбросить» или
 * «уйти», пачкой у человека не считается.
 *
 * Считаются пачки, а не количества: разные единицы не складываются. Цена — цена **всей пачки**,
 * как её назвал человек, по валютам отдельно; пачки без цены — отдельным числом, а не бесплатными.
 */
data class StockSummary(
    val packages: Int,
    val byCategory: List<CategoryCount>,
    val byForm: List<FormCount>,
    val prices: List<Money>,
    val unpriced: Int
) {

    /** Пачек в категории; `null` — категория не указана. */
    data class CategoryCount(val category: String?, val packages: Int)

    /** Пачек в форме; `null` — форма не указана. */
    data class FormCount(val form: DosageForm?, val packages: Int)

    companion object {

        /**
         * Сводка по пачкам [stock]. Группы — от больших к меньшим, при равенстве по названию, «не
         * указано» — последним; цены — по коду валюты.
         */
        fun of(stock: List<Package>): StockSummary {
            val living = stock.filter { it.status.allowsUse }
            val byCategory = living.groupBy { it.facts.category }
                .map { (category, group) -> CategoryCount(category, group.size) }
                .sortedWith(compareBy<CategoryCount> { it.category == null }.thenByDescending { it.packages }.thenBy { it.category })
            val byForm = living.groupBy { it.facts.form }
                .map { (form, group) -> FormCount(form, group.size) }
                .sortedWith(compareBy<FormCount> { it.form == null }.thenByDescending { it.packages }.thenBy { it.form?.name })
            val priced = living.mapNotNull { it.facts.price }
            val prices = priced.groupBy { it.currency }
                .map { (currency, group) -> Money(group.fold(BigDecimal.ZERO) { sum, money -> sum + money.amount }, currency) }
                .sortedBy { it.currencyCode }
            return StockSummary(living.size, byCategory, byForm, prices, living.size - priced.size)
        }
    }
}
