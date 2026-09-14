package com.kert0n.medapp.domain.scan

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.DosageForm

/**
 * Что код предлагает подставить в форму новой пачки (PLAN H5) — **предложение, а не факт**: срок
 * — сведения каталога, а не этой коробки, и экран показывает его с источником; дозировка и
 * количество — строки для глаз, полей `Quantity` и `Dose` здесь нет, и превратить «20 капсул в 2
 * блистерах» или «500 мг» в число нечем. Форма — двумя частями: [formText] — как её назвал
 * реестр, всегда, и [form] — та форма словаря, которую в этом тексте узнали, либо ничего: словари
 * получены разными путями, догадок и выбора из похожих нет, а текст реестра человек видит в любом
 * случае. Всё необязательно, кроме [isMedicine]: категория вне лекарств — предупреждение, а не
 * молчаливое автозаполнение.
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
    val isMedicine: Boolean
)
