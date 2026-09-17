package com.kert0n.medapp.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SettingsChanging
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.presentation.ParsedInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Форма настроек (PLAN H3 №27): пороги и время уведомлений, интервал обмена.
 *
 * Записанное читается **один раз** при открытии: форма — про то, что человек набирает, и новое
 * значение из хранилища, пришедшее пока он печатает, затёрло бы его ввод (U1). Пишет настройки
 * только сценарий, и он же их применяет — экран о применении не знает.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val changing: SettingsChanging
) : ViewModel() {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)

    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = SettingsUiState.Editing(store.observe().first().toFormPresentationDTO())
        }
    }

    fun edit(form: SettingsFormPresentationDTO) {
        val editing = _state.value as? SettingsUiState.Editing ?: return
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        _state.value = editing.copy(form = form, error = null)
    }

    /**
     * Записать и применить. Второе нажатие, пока идёт первое или когда уже записано, не делает
     * ничего: сторожем служит само состояние, и ставится оно **до** обращения к сценарию.
     */
    fun save() {
        val editing = _state.value as? SettingsUiState.Editing ?: return
        if (editing.isSaving || editing.isSaved) return
        when (val parsed = editing.form.parsed()) {
            is ParsedInput.Rejected -> _state.value = editing.copy(error = parsed.error)
            is ParsedInput.Parsed -> {
                val saving = editing.copy(error = null, isSaving = true)
                _state.value = saving
                viewModelScope.launch { write(saving, parsed.value) }
            }
        }
    }

    private suspend fun write(saving: SettingsUiState.Editing, settings: AppSettings) {
        _state.value = when (changing.change(settings)) {
            SettingsChanging.Outcome.SAVED -> saving.copy(isSaving = false, isSaved = true)
            // Не легло — прежние настройки действуют, и человеку об этом говорят, а ввод цел.
            SettingsChanging.Outcome.NOT_SAVED -> saving.copy(isSaving = false, error = SettingsFormError.NotSaved)
        }
    }
}

/**
 * Что показывает форма настроек. Случая два: [Loading] — ждём записанное, [Editing] — человек
 * правит. «Нет» у настроек не бывает: повреждённое читается умолчаниями (PLAN C1).
 */
sealed interface SettingsUiState {

    data object Loading : SettingsUiState

    data class Editing(
        val form: SettingsFormPresentationDTO,
        val error: SettingsFormError? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false
    ) : SettingsUiState
}
