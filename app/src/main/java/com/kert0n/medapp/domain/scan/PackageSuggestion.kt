package com.kert0n.medapp.domain.scan

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.DosageForm
import java.time.LocalDate

/**
 * Что код предлагает подставить в форму новой пачки (PLAN H5) — **предложение, а не факт**: срок
 * — сведения каталога, а не этой коробки, и экран показывает его с источником; дозировка и
 * количество — строки для глаз, полей `Quantity` и `Dose` здесь нет, и превратить «20 капсул в 2
 * блистерах» или «500 мг» в число нечем. Форма — двумя частями: [formText] — как её назвал
 * реестр, всегда, и [form] — лучшая догадка словаря по этому тексту, одна: она подставляется
 * сразу, а ошибётся — человек поправит; словари получены разными путями, и текст реестра он
 * видит в любом случае.
 *
 * [boughtOn] — день, когда коробку продали: реестр знает его, потому что кассовый чек через него и
 * прошёл, и для человека это и есть дата покупки.
 *
 * Всё необязательно, кроме [isMedicine]: категория вне лекарств — предупреждение, а не молчаливое
 * автозаполнение.
 */
data class PackageSuggestion(
    val name: String? = null,
    val formText: String? = null,
    val form: DosageForm? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val expiresOn: ExpiryDate? = null,
    val activeSubstance: String? = null,
    val dosageText: String? = null,
    val quantityText: String? = null,
    val boughtOn: LocalDate? = null,
    val isMedicine: Boolean
)
