package com.kert0n.medapp.presentation.course

/**
 * Почему форму не записать. Причина — значение, а не текст: слова подбирает экран
 * (`R.string.*`), а разбор и сценарий называют случай (PLAN H1).
 */
sealed interface CourseFormError {

    /** Названо человеком неверно — и названо **какое** поле: иначе он ищет ошибку глазами. */
    enum class Input : CourseFormError { TITLE_EMPTY, TITLE_TOO_LONG, NOTE_TOO_LONG }

    /** Черновик правили с другого экрана: то, что здесь, устарело, и писать поверх нельзя (PLAN F5). */
    data object Stale : CourseFormError
}
