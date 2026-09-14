package com.kert0n.medapp.domain.scan

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.DosageForm

/**
 * Что код предлагает подставить в форму новой пачки (PLAN H5) — **предложение, а не факт**: срок
 * — сведения каталога, а не этой коробки, и экран показывает его с источником; дозировка и
 * количество — строки для глаз, полей `Quantity` и `Dose` здесь нет, и превратить «20 капсул в 2
 * блистерах» или «500 мг» в число нечем. Всё необязательно, кроме [isMedicine]: категория вне
 * лекарств — предупреждение, а не молчаливое автозаполнение.
 */
data class PackageSuggestion(
    val name: String? = null,
    val form: FormSuggestion = FormSuggestion.None,
    val manufacturer: String? = null,
    val country: String? = null,
    val expiresOn: ExpiryDate? = null,
    val activeSubstance: String? = null,
    val dosageText: String? = null,
    val quantityText: String? = null,
    val isMedicine: Boolean
)

/** Форма по тексту ответа — три случая, потому что экран делает три вещи: подставить, дать выбрать, оставить пустым. */
sealed interface FormSuggestion {

    data class One(val form: DosageForm) : FormSuggestion

    data class Several(val forms: List<DosageForm>) : FormSuggestion {
        init {
            require(forms.size > 1) { "несколько — это больше одной" }
        }
    }

    data object None : FormSuggestion

    companion object {
        fun of(forms: List<DosageForm>): FormSuggestion = when (forms.size) {
            0 -> None
            1 -> One(forms.single())
            else -> Several(forms)
        }
    }
}
