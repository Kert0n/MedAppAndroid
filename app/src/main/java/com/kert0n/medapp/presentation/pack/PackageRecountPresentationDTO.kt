package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.QuantityPresentationError

/**
 * Пересчёт в том виде, в каком его держит форма: строкой, как человек напечатал. Пока он
 * печатает, там лежит «1,» или пусто, и количеством это ещё не является (PLAN H1). Поле одно:
 * человек называет то, что видит целиком, а разницу считает учёт (H3 №9).
 */
data class PackageRecountPresentationDTO(val amount: String = "")

/**
 * Почему число не записалось. Текст по причине берёт экран из `R.string.*`: представление не
 * решает, на каком языке говорит приложение.
 */
sealed interface PackageRecountError {

    /** Разбор не дал числа: пусто, не число, слишком длинное. */
    data class Amount(val reason: QuantityPresentationError) : PackageRecountError

    /** Коробки больше нет. */
    data object Gone : PackageRecountError

    /** Коробка ждёт ответа сервера на другое решение (PLAN E1). */
    data object Busy : PackageRecountError
}
