package com.kert0n.medapp.presentation.medkit

/**
 * Почему форму не записать. Причина — значение, а не текст: слова подбирает экран
 * (`R.string.*`), а сценарий и разбор называют случай (PLAN H1).
 */
sealed interface MedKitFormError {

    /** Названо человеком неверно — и названо **какое** поле: иначе он ищет ошибку глазами. */
    enum class Input : MedKitFormError { NAME_EMPTY, NAME_TOO_LONG, LOCATION_TOO_LONG }

    /** Полка ждёт ответа сервера на другое решение: правку поверх него деть некуда (PLAN E1). */
    data object Busy : MedKitFormError
}
