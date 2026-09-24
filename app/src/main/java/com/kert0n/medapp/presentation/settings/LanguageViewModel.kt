package com.kert0n.medapp.presentation.settings

import androidx.lifecycle.ViewModel
import com.kert0n.medapp.feature.settings.AppLanguage
import com.kert0n.medapp.feature.settings.AppLanguages
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Язык приложения (PLAN H3 №27): что выбрано, читается у системы, выбор уходит ей же и
 * применяется сразу — окно пересоздаётся, а модель переживает это и показывает уже новое.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(private val languages: AppLanguages) : ViewModel() {

    private val _state = MutableStateFlow(languages.current().toChoice())

    val state: StateFlow<LanguageChoice> = _state.asStateFlow()

    fun choose(choice: LanguageChoice) {
        if (choice == _state.value) return
        languages.choose(choice.toAppLanguage())
        _state.value = languages.current().toChoice()
    }
}

/**
 * Выбор языка словами экрана: те же три случая, что у порта, — экран платформы не видит, и
 * значение порта на него не едет (PLAN H1).
 */
enum class LanguageChoice { SYSTEM, RUSSIAN, ENGLISH }

fun AppLanguage.toChoice(): LanguageChoice = when (this) {
    AppLanguage.SYSTEM -> LanguageChoice.SYSTEM
    AppLanguage.RUSSIAN -> LanguageChoice.RUSSIAN
    AppLanguage.ENGLISH -> LanguageChoice.ENGLISH
}

fun LanguageChoice.toAppLanguage(): AppLanguage = when (this) {
    LanguageChoice.SYSTEM -> AppLanguage.SYSTEM
    LanguageChoice.RUSSIAN -> AppLanguage.RUSSIAN
    LanguageChoice.ENGLISH -> AppLanguage.ENGLISH
}
