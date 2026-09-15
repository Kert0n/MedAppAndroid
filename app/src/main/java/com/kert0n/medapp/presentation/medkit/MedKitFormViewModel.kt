package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.medkits.MedKitKeeping
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Заведение и правка аптечки (PLAN H3 №3).
 *
 * Какую полку правят, берётся из [SavedStateHandle], а не из вызова экрана: аргумент маршрута
 * обязан пережить смерть процесса, и открывать форму заново «по appearance» значило бы потерять
 * место, где человек стоял (PLAN H3).
 *
 * Записанное дочитывается **один раз**, при открытии: подписка на полку перетирала бы то, что
 * человек уже напечатал, каждым изменением в базе (наследство разбора #16).
 */
@HiltViewModel
class MedKitFormViewModel @Inject constructor(
    private val keeping: MedKitKeeping,
    private val medKits: MedKitStorageRepository,
    private val today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val medKitId: Uuid? =
        savedState.get<String>(RouteArguments.MED_KIT_ID)?.takeIf { it != "null" }?.let(Uuid::parse)

    private val _state = MutableStateFlow<MedKitFormUiState>(
        if (medKitId == null) MedKitFormUiState.Editing() else MedKitFormUiState.Loading
    )

    val state: StateFlow<MedKitFormUiState> = _state.asStateFlow()

    init {
        if (medKitId != null) viewModelScope.launch { open(medKitId) }
    }

    fun edit(form: MedKitFormPresentationDTO) {
        val editing = _state.value as? MedKitFormUiState.Editing ?: return
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        _state.value = editing.copy(form = form, error = null)
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, не делает ничего: человек
     * нажимает ещё раз, не дождавшись ответа, и ждёт от этого того же самого, а не второй полки.
     * Сторожем служит само состояние, а не отдельный признак рядом с ним, — разъехаться им нечем.
     */
    fun save() {
        val editing = _state.value as? MedKitFormUiState.Editing ?: return
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

    private suspend fun open(medKitId: Uuid) {
        val stored = medKits.observe(medKitId, today.observe().first()).first()
        // Полки нет — это отказ, а не пустая форма: пустую человек заполнил бы и не понял, куда
        // делась его правка (наследство разбора #16).
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
 * Что показывает форма полки. Случаи различает то, что человек может сделать: при [Loading] —
 * ждать, при [Gone] — уйти, при [Editing] — править и сохранять.
 */
sealed interface MedKitFormUiState {

    data object Loading : MedKitFormUiState

    /** Полки, которую открыли, больше нет: править нечего, и форма об этом говорит. */
    data object Gone : MedKitFormUiState

    /**
     * [isEditing] — правят записанную полку или заводят новую. Спрашивать это у полей нельзя:
     * новая форма с напечатанным названием стала бы «правкой», а пустое название у записанной
     * полки — «заведением».
     */
    data class Editing(
        val form: MedKitFormPresentationDTO = MedKitFormPresentationDTO(),
        val isEditing: Boolean = false,
        val error: MedKitFormError? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false
    ) : MedKitFormUiState
}
