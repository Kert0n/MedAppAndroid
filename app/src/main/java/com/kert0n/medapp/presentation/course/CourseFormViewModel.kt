package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Редактор лечения (PLAN H3 №15). Без номера — новый черновик; с номером — записанный.
 *
 * Черновик записывается **с одним названием**: остальное человек дописывает, когда узнает
 * (D5). Записанное дочитывается один раз, до первого ввода: экран показывает то, что человек
 * видел, и не переписывает то, что он печатает (U1). При записи уходит только изменённое:
 * назначение черновика правится по частям, и нетронутое поле сценарию не называют. Аргумент
 * приходит значением из ключа маршрута, а не из `SavedStateHandle` (H3 «Оболочка»).
 */
@HiltViewModel(assistedFactory = CourseFormViewModel.Factory::class)
class CourseFormViewModel @AssistedInject constructor(
    private val drafting: CourseDrafting,
    private val courses: CourseStorageRepository,
    private val vocabulary: VocabularyStorageRepository,
    @Assisted private val courseId: Uuid?
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Uuid?): CourseFormViewModel
    }

    private val editing = MutableStateFlow<CourseFormUiState>(
        if (courseId == null) CourseFormUiState.Editing(CourseFormPresentationDTO(), CourseFormUiState.Mode.NEW_DRAFT)
        else CourseFormUiState.Loading
    )

    /** Из чего человек выбирает: словарь. Приходит к форме, а не в неё — редактор её не пишет. */
    val state: StateFlow<CourseFormUiState> = combine(
        editing,
        vocabulary.observeUnits(),
        vocabulary.observeForms()
    ) { state, units, forms ->
        if (state is CourseFormUiState.Editing) {
            state.copy(units = units.map { it.toPresentationDTO() }, forms = forms.map { it.toPresentationDTO() })
        } else state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), editing.value)

    init {
        if (courseId != null) viewModelScope.launch { open(courseId) }
    }

    fun edit(form: CourseFormPresentationDTO) {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        editing.value = current.copy(form = form, error = null, expectedEnd = form.expectedEnd())
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, не делает ничего:
     * сторожем служит само состояние, а не признак рядом с ним.
     */
    fun save() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        if (current.isBusy || current.isSaved) return
        val saving = current.copy(error = null, isSaving = true)
        editing.value = saving
        viewModelScope.launch {
            when (val parsed = current.form.parsed(vocabulary.snapshot())) {
                is ParsedInput.Rejected -> editing.value = saving.copy(isSaving = false, error = parsed.error)
                is ParsedInput.Parsed -> write(saving, parsed.value)
            }
        }
    }

    /** Удаление спрашивается **до** сценария: в черновике может лежать единственная запись от врача (H3). */
    fun askToDiscard() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        if (current.mode == CourseFormUiState.Mode.DRAFT) editing.value = current.copy(asksToDiscard = true)
    }

    fun dismissDiscard() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        editing.value = current.copy(asksToDiscard = false)
    }

    fun discard() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        if (!current.asksToDiscard || current.isBusy) return
        val id = courseId ?: return
        editing.value = current.copy(asksToDiscard = false, isDiscarding = true)
        viewModelScope.launch {
            // Удалять нечего — черновика уже нет: итог для человека тот же, экран уходит.
            drafting.discard(id)
            editing.value = current.copy(asksToDiscard = false, isDiscarding = false, isDiscarded = true)
        }
    }

    private suspend fun open(id: Uuid) {
        val draft = courses.observeDrafts().first().firstOrNull { it.id == id }
        // Черновика нет — это отказ, а не пустая форма: заполненную человек сохранил бы и не
        // понял, куда делась его правка.
        editing.value = draft?.let {
            val form = it.toFormPresentationDTO()
            CourseFormUiState.Editing(form, CourseFormUiState.Mode.DRAFT, stored = it, expectedEnd = form.expectedEnd())
        } ?: CourseFormUiState.Gone
    }

    private suspend fun write(saving: CourseFormUiState.Editing, described: CourseDescription) {
        editing.value = when (saving.mode) {
            CourseFormUiState.Mode.NEW_DRAFT -> {
                val created = drafting.create(described.title, described.note)
                val edits = described.editsSince(created)
                if (edits.isEmpty()) saving.copy(isSaving = false, isSaved = true)
                else saving.told(drafting.edit(created.id, created.revision, edits))
            }
            CourseFormUiState.Mode.DRAFT -> {
                val stored = checkNotNull(saving.stored)
                val edits = described.editsSince(stored)
                if (edits.isEmpty()) saving.copy(isSaving = false, isSaved = true)
                else saving.told(drafting.edit(stored.id, stored.revision, edits))
            }
        }
    }

    /** Только изменённое: нетронутое поле сценарию не называют — черновик правится по частям. */
    private fun CourseDescription.editsSince(stored: CourseDraftProjection): List<CourseDrafting.Edit> = buildList {
        if (title != stored.title || note != stored.note) add(CourseDrafting.Edit.Rename(title, note))
        dose?.takeIf { it != stored.dose }?.let { add(CourseDrafting.Edit.SetDose(it)) }
        form?.takeIf { it != stored.form }?.let { add(CourseDrafting.Edit.SetForm(it)) }
        schedule?.takeIf { it != stored.schedule }?.let { add(CourseDrafting.Edit.SetSchedule(it)) }
        totalDoses?.takeIf { it != stored.totalDoses }?.let { add(CourseDrafting.Edit.SetTotalDoses(it)) }
    }

    /** Чем кончилась запись — человеку: записано; черновика нет; устарел; отказ по месту. */
    private fun CourseFormUiState.Editing.told(outcome: CourseDrafting.Outcome): CourseFormUiState = when (outcome) {
        is CourseDrafting.Outcome.Saved -> copy(isSaving = false, isSaved = true)
        CourseDrafting.Outcome.Gone -> CourseFormUiState.Gone
        CourseDrafting.Outcome.Stale -> copy(isSaving = false, error = CourseFormError.Stale)
        is CourseDrafting.Outcome.Rejected -> copy(isSaving = false, error = CourseFormError.Rejected(outcome.reason))
        // Пачки редактор не трогает: до этого исхода его правки не доходят.
        CourseDrafting.Outcome.PackageUnusable -> error("редактор назначения пачек не подключает: $outcome")
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
        /** Записанное, с которым сравнивают при записи: уходит только изменённое. */
        val stored: CourseDraftProjection? = null,
        val units: List<UnitPresentationDTO> = emptyList(),
        val forms: List<FormPresentationDTO> = emptyList(),
        /** Когда ожидается последний приём по тому, что набрано; нечего считать — `null`. */
        val expectedEnd: LocalDate? = null,
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
