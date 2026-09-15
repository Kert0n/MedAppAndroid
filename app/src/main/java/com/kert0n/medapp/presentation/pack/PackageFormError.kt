package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.ExpiryDatePresentationError
import com.kert0n.medapp.presentation.value.MoneyPresentationError
import com.kert0n.medapp.presentation.value.QuantityPresentationError

/**
 * Почему форма упаковки не годится. Причина называет **поле**: полей четырнадцать, и «где-то
 * ошибка» заставило бы человека искать её глазами. Текст по причине берёт экран из `R.string.*`:
 * маппер не решает, на каком языке говорит приложение (PLAN H1).
 */
sealed interface PackageFormError {

    val field: Field

    /** Поля, которые бывают неверными. Экран подсвечивает названное и раскрывает его раздел. */
    enum class Field {
        MED_KIT, NAME, AMOUNT, UNIT,
        FORM, CATEGORY, MANUFACTURER, COUNTRY, DESCRIPTION, EXPIRY, HINT, NOTE, PRICE;

        /**
         * Обязательных полей четыре (PLAN C1); остальные лежат в раскрываемом разделе. Знание это
         * принадлежит полю, а не экрану: иначе список обязательного разъехался бы с формой.
         */
        val isRequired: Boolean
            get() = this == MED_KIT || this == NAME || this == AMOUNT || this == UNIT
    }

    /** Не выбрана аптечка: упаковка лежит в каком-то одном месте, и «нигде» её не бывает. */
    data object MedKitMissing : PackageFormError {
        override val field: Field get() = Field.MED_KIT
    }

    /** Не выбрана единица: без неё количество ничего не измеряет (PLAN D1). */
    data object UnitMissing : PackageFormError {
        override val field: Field get() = Field.UNIT
    }

    /** Безымянная упаковка не находится ни поиском, ни глазами. */
    data object NameEmpty : PackageFormError {
        override val field: Field get() = Field.NAME
    }

    /** Предел держит тип сведений; здесь названо только поле, которое его перешло. */
    data class TooLong(override val field: Field) : PackageFormError

    data class Amount(val reason: QuantityPresentationError) : PackageFormError {
        override val field: Field get() = Field.AMOUNT
    }

    /**
     * Пустой коробки не бывает: её заводят, чтобы что-то в ней лежало. Отдельно от [Amount],
     * потому что ноль — законное число, которое разбор пропускает, а заведение отвергает.
     */
    data object AmountIsZero : PackageFormError {
        override val field: Field get() = Field.AMOUNT
    }

    data class Hint(val reason: QuantityPresentationError) : PackageFormError {
        override val field: Field get() = Field.HINT
    }

    /** Нулевая доза — не доза, а деление на ноль в обеспечении (PLAN D1). */
    data object HintIsZero : PackageFormError {
        override val field: Field get() = Field.HINT
    }

    data class Expiry(val reason: ExpiryDatePresentationError) : PackageFormError {
        override val field: Field get() = Field.EXPIRY
    }

    data class Price(val reason: MoneyPresentationError) : PackageFormError {
        override val field: Field get() = Field.PRICE
    }

    /**
     * Названной единицы или формы в снимке словаря нет: снимок старее, чем тот, кто её назвал
     * (PLAN D1). Отказ, а не падение.
     */
    data class UnknownInVocabulary(override val field: Field) : PackageFormError

    /**
     * Полки, в которую кладут, больше нет, либо она ждёт ответа сервера на своё решение — поле
     * тут ни при чём, но сказать об этом надо там же, где человек нажал (PLAN E1).
     */
    data object MedKitGone : PackageFormError {
        override val field: Field get() = Field.MED_KIT
    }

    data object MedKitBusy : PackageFormError {
        override val field: Field get() = Field.MED_KIT
    }
}
