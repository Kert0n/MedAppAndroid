package com.kert0n.medapp.presentation.settings

/**
 * Почему настройки не записать. Причина — значение, а не текст: слова подбирает экран
 * (`R.string.*`), а разбор и сценарий называют случай (PLAN H1).
 */
sealed interface SettingsFormError {

    /** Набрано неверно — и названо **какое** поле: иначе человек ищет ошибку глазами. */
    enum class Input : SettingsFormError {
        SNOOZE_NOT_A_NUMBER, SNOOZE_NOT_FORWARD,
        THRESHOLD_NOT_A_NUMBER, THRESHOLD_NEGATIVE
    }

    /** Записать не удалось; прежние настройки действуют дальше (`SettingsSaved.LOST`). */
    data object NotSaved : SettingsFormError
}
