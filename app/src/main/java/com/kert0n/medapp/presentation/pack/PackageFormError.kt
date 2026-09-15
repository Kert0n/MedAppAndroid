package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.ExpiryDatePresentationError
import com.kert0n.medapp.presentation.value.MoneyPresentationError
import com.kert0n.medapp.presentation.value.QuantityPresentationError

/**
 * Почему упаковку не записать. Причина называет **поле**: человек иначе ищет ошибку глазами по
 * всей форме. Слова подбирает экран (`R.string.*`) — причина остаётся значением (PLAN H1).
 */
sealed interface PackageFormError {

    /** Какое поле не приняли: по нему экран и подсвечивает. */
    val field: Field

    enum class Field { MED_KIT, NAME, AMOUNT, UNIT, FORM, EXPIRY, PRICE, HINT, TEXT }

    data object MedKitMissing : PackageFormError {
        override val field = Field.MED_KIT
    }

    data object NameEmpty : PackageFormError {
        override val field = Field.NAME
    }

    data object UnitMissing : PackageFormError {
        override val field = Field.UNIT
    }

    /** Пустую упаковку заводить незачем: количество — то, ради чего её и записывают. */
    data object AmountIsZero : PackageFormError {
        override val field = Field.AMOUNT
    }

    data class Amount(val reason: QuantityPresentationError) : PackageFormError {
        override val field = Field.AMOUNT
    }

    data class Expiry(val reason: ExpiryDatePresentationError) : PackageFormError {
        override val field = Field.EXPIRY
    }

    data class Price(val reason: MoneyPresentationError) : PackageFormError {
        override val field = Field.PRICE
    }

    data class Hint(val reason: QuantityPresentationError) : PackageFormError {
        override val field = Field.HINT
    }

    /** Нулевой дозы-подсказки не бывает: это не «не указана», а бессмыслица. */
    data object HintIsZero : PackageFormError {
        override val field = Field.HINT
    }

    /** Текстовое поле длиннее того, что примет домен; названо, какое именно. */
    data class TooLong(val of: Field, val limit: Int) : PackageFormError {
        override val field = of
    }

    /** Полки нет — писать некуда. */
    data object MedKitGone : PackageFormError {
        override val field = Field.MED_KIT
    }

    /** Полка ждёт ответа сервера на другое решение (PLAN E1). */
    data object MedKitBusy : PackageFormError {
        override val field = Field.MED_KIT
    }

    /** Коробка ждёт ответа на своё решение: правку поверх него деть некуда. */
    data object PackageBusy : PackageFormError {
        override val field = Field.NAME
    }

    /** Коробки больше нет: правку писать некуда. */
    data object PackageGone : PackageFormError {
        override val field = Field.NAME
    }

    /** Формы нет в снимке словаря: он старее, чем тот, кто её назвал (PLAN D1). */
    data object FormUnknown : PackageFormError {
        override val field = Field.FORM
    }

    /** У общей упаковки форму нельзя стереть — только заменить другой (сервер не примет). */
    data object FormClearUnsupported : PackageFormError {
        override val field = Field.FORM
    }
}
