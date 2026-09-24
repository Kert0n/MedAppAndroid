package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.medkits.MedKitKeeping
import com.kert0n.medapp.feature.medkits.MedKitReadings
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.act
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Создание и правка аптечки (PLAN H3 №3).
 *
 * Аргумент приходит **значением из ключа маршрута**, а не из `SavedStateHandle`: ключ уже
 * пережил смерть процесса в сохранённой стопке, и оболочка отдаёт из него то, что нужно экрану.
 * Сам ключ сюда не заходит: навигация — не дело представления (границы H1).
 */
@HiltViewModel(assistedFactory = MedKitFormViewModel.Factory::class)
class MedKitFormViewModel @AssistedInject constructor(
    private val keeping: MedKitKeeping,
    private val medKits: MedKitReadings,
    private val today: Today,
    @Assisted private val medKitId: Uuid?,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    @AssistedFactory
    interface Factory {
        fun create(medKitId: Uuid?): MedKitFormViewModel
    }

    private val _state = MutableStateFlow<MedKitFormUiState>(
        if (medKitId == null) MedKitFormUiState.Editing(MedKitFormPresentationDTO()) else MedKitFormUiState.Loading
    )

    val state: StateFlow<MedKitFormUiState> = _state.asStateFlow()

    init {
        if (medKitId != null) reading.load(viewModelScope) { open(medKitId) }
    }

    fun edit(form: MedKitFormPresentationDTO) {
        val editing = _state.value as? MedKitFormUiState.Editing ?: return
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        _state.value = editing.copy(form = form, error = null)
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, не делает ничего:
     * человек нажимает ещё раз, не дождавшись ответа, и ждёт от этого того же самого, а не
     * второй полки. Сторожем служит само состояние, а не признак рядом с ним, — разъехаться им
     * нечем.
     */
    fun save() {
        val editing = _state.value as? MedKitFormUiState.Editing ?: return
        if (editing.isSaving || editing.isSaved) return
        when (val parsed = editing.form.parsed()) {
            is ParsedInput.Rejected -> _state.value = editing.copy(error = parsed.error)
            is ParsedInput.Parsed -> {
                val saving = editing.copy(error = null, isSaving = true)
                _state.value = saving
                act(failures, undo = { (_state.value as? MedKitFormUiState.Editing)?.let { _state.value = it.copy(isSaving = false) } }) {
                    write(saving, parsed.value)
                }
            }
        }
    }

    private suspend fun open(medKitId: Uuid) {
        val stored = medKits.observe(medKitId, today.observe().first().date).first()
        // Полки нет — это отказ, а не пустая форма: пустую человек заполнил бы и не понял, куда
        // делась его правка.
        _state.value = stored?.let { MedKitFormUiState.Editing(it.toFormPresentationDTO(), isEditing = true) }
            ?: MedKitFormUiState.Gone
    }

    private suspend fun write(saving: MedKitFormUiState.Editing, description: MedKitDescription) {
        if (medKitId == null) {
            keeping.create(description.name, description.location)
            _state.value = saving.copy(isSaving = false, isSaved = true)
            return
        }
        _state.value = when (keeping.describe(medKitId, description.name, description.location)) {
            MedKitKeeping.Outcome.SAVED -> saving.copy(isSaving = false, isSaved = true)
            MedKitKeeping.Outcome.GONE -> MedKitFormUiState.Gone
            MedKitKeeping.Outcome.BUSY -> saving.copy(isSaving = false, error = MedKitFormError.Busy)
        }
    }
}

/**
 * Что показывает форма. Три случая, и человек в каждом делает разное: [Loading] — ждёт
 * записанное, [Gone] — узнаёт, что полки нет, [Editing] — печатает.
 */
sealed interface MedKitFormUiState {

    data object Loading : MedKitFormUiState

    /** Полки нет: правка ей не поможет, и форму показывать незачем. */
    data object Gone : MedKitFormUiState

    data class Editing(
        val form: MedKitFormPresentationDTO,
        val isEditing: Boolean = false,
        val error: MedKitFormError? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false
    ) : MedKitFormUiState
}
