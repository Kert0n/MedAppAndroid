package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.QuantityPresentationError

/**
 * Что человек делает с числом в коробке. Случая два, и различает их не результат, а объяснение:
 * оба меняют остаток, но «пересчитал» называет **новое число целиком**, а «выбросил» — то,
 * сколько ушло. Вычитание за человека делает учёт (PLAN E1).
 */
enum class AmountChange {

    /** «Пересчитал и увидел столько» — замена значения, а не разница. */
    RECOUNT,

    /** «Выбросил столько» — разница; в минус коробка не уходит. */
    DISPOSAL
}

/**
 * Пересчёт в том виде, в каком его держит форма: строкой, как человек напечатал. Пока он
 * печатает, там лежит «1,» или пусто, и количеством это ещё не является (PLAN H1).
 */
data class PackageAmountPresentationDTO(
    val change: AmountChange = AmountChange.RECOUNT,
    val amount: String = ""
)

/**
 * Почему число не записалось. Текст по причине берёт экран из `R.string.*`: маппер не решает, на
 * каком языке говорит приложение.
 */
sealed interface PackageAmountError {

    /** Разбор не дал числа: пусто, не число, слишком длинное. */
    data class Amount(val reason: QuantityPresentationError) : PackageAmountError

    /** Выбросить ноль — это ничего не сделать, и записывать тут нечего. */
    data object NothingToDispose : PackageAmountError

    /** Выбросить больше, чем лежит, нельзя: в минус коробка не уходит (PLAN D3). */
    data object MoreThanThereIs : PackageAmountError

    /** Коробки больше нет. */
    data object Gone : PackageAmountError

    /** Коробка ждёт ответа сервера на решение о себе (PLAN E1). */
    data object Busy : PackageAmountError
}
