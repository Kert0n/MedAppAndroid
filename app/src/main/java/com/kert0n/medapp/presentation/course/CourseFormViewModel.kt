package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.storage.course.CourseStorageRepository
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
 * Редактор лечения (PLAN H3 №15). Без номера — новый черновик; с номером — записанный.
 *
 * Черновик записывается **с одним названием**: остальное человек дописывает, когда узнает
 * (D5). Записанное дочитывается один раз, до первого ввода: экран показывает то, что человек
 * видел, и не переписывает то, что он печатает (U1). Аргумент приходит значением из ключа
 * маршрута, а не из `SavedStateHandle` (H3 «Оболочка»).
 */
@HiltViewModel(assistedFactory = CourseFormViewModel.Factory::class)
class CourseFormViewModel @AssistedInject constructor(
    private val drafting: CourseDrafting,
    private val courses: CourseStorageRepository,
    @Assisted private val courseId: Uuid?
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Uuid?): CourseFormViewModel
    }

    private val _state = MutableStateFlow<CourseFormUiState>(
        if (courseId == null) CourseFormUiState.Editing(CourseFormPresentationDTO(), CourseFormUiState.Mode.NEW_DRAFT)
        else CourseFormUiState.Loading
    )

    val state: StateFlow<CourseFormUiState> = _state.asStateFlow()

    init {
        if (courseId != null) viewModelScope.launch { open(courseId) }
    }

    fun edit(form: CourseFormPresentationDTO) {
        val editing = _state.value as? CourseFormUiState.Editing ?: return
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        _state.value = editing.copy(form = form, error = null)
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, не делает ничего:
     * сторожем служит само состояние, а не признак рядом с ним.
     */
    fun save() {
        val editing = _state.value as? CourseFormUiState.Editing ?: return
        if (editing.isBusy || editing.isSaved) return
        when (val parsed = editing.form.parsed()) {
            is ParsedInput.Rejected -> _state.value = editing.copy(error = parsed.error)
            is ParsedInput.Parsed -> {
                val saving = editing.copy(error = null, isSaving = true)
                _state.value = saving
                viewModelScope.launch { write(saving, parsed.value) }
            }
        }
    }

    /** Удаление спрашивается **до** сценария: в черновике может лежать единственная запись от врача (H3). */
    fun askToDiscard() {
        val editing = _state.value as? CourseFormUiState.Editing ?: return
        if (editing.mode == CourseFormUiState.Mode.DRAFT) _state.value = editing.copy(asksToDiscard = true)
    }

    fun dismissDiscard() {
        val editing = _state.value as? CourseFormUiState.Editing ?: return
        _state.value = editing.copy(asksToDiscard = false)
    }

    fun discard() {
        val editing = _state.value as? CourseFormUiState.Editing ?: return
        if (!editing.asksToDiscard || editing.isBusy) return
        val id = courseId ?: return
        _state.value = editing.copy(asksToDiscard = false, isDiscarding = true)
        viewModelScope.launch {
            // Удалять нечего — черновика уже нет: итог для человека тот же, экран уходит.
            drafting.discard(id)
            _state.value = editing.copy(asksToDiscard = false, isDiscarding = false, isDiscarded = true)
        }
    }

    private suspend fun open(id: Uuid) {
        val draft = courses.observeDrafts().first().firstOrNull { it.id == id }
        // Черновика нет — это отказ, а не пустая форма: заполненную человек сохранил бы и не
        // понял, куда делась его правка.
        _state.value = draft?.let {
            CourseFormUiState.Editing(it.toFormPresentationDTO(), CourseFormUiState.Mode.DRAFT, revision = it.revision)
        } ?: CourseFormUiState.Gone
    }

    private suspend fun write(saving: CourseFormUiState.Editing, description: CourseDescription) {
        _state.value = when (saving.mode) {
            CourseFormUiState.Mode.NEW_DRAFT -> {
                drafting.create(description.title, description.note)
                saving.copy(isSaving = false, isSaved = true)
            }
            CourseFormUiState.Mode.DRAFT -> {
                val id = checkNotNull(courseId)
                val revision = checkNotNull(saving.revision)
                val edits = listOf(CourseDrafting.Edit.Rename(description.title, description.note))
                when (val outcome = drafting.edit(id, revision, edits)) {
                    is CourseDrafting.Outcome.Saved -> saving.copy(isSaving = false, isSaved = true)
                    CourseDrafting.Outcome.Gone -> CourseFormUiState.Gone
                    CourseDrafting.Outcome.Stale -> saving.copy(isSaving = false, error = CourseFormError.Stale)
                    // Название и заметку черновик не отвергает: до отказов доходят только доза, форма и пачки.
                    is CourseDrafting.Outcome.Rejected, CourseDrafting.Outcome.PackageUnusable ->
                        error("переименование черновика не отвергается: $outcome")
                }
            }
        }
    }
}

/**
 * Что показывает редактор. Три случая, и человек в каждом делает разное: [Loading] — ждёт
 * записанное, [Gone] — узнаёт, что черновика нет, [Editing] — печатает.
 */
sealed interface CourseFormUiState {

    data object Loading : CourseFormUiState

    /** Черновика нет: правка ему не поможет, и форму показывать незачем. */
    data object Gone : CourseFormUiState

    data class Editing(
        val form: CourseFormPresentationDTO,
        val mode: Mode,
        val revision: Revision? = null,
        val error: CourseFormError? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val asksToDiscard: Boolean = false,
        val isDiscarding: Boolean = false,
        val isDiscarded: Boolean = false
    ) : CourseFormUiState {
        val isBusy: Boolean get() = isSaving || isDiscarding
    }

    /** Чем открыт редактор: новым черновиком, записанным черновиком. Идущее лечение — U3 №9. */
    enum class Mode { NEW_DRAFT, DRAFT }
}
