package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.feature.course.CourseActivation
import com.kert0n.medapp.feature.course.CourseAmendment
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.CourseRenaming
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
    private val activation: CourseActivation,
    private val amendment: CourseAmendment,
    private val renaming: CourseRenaming,
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

    /**
     * Открыть источники. У нового черновика их некуда подключать, пока он не записан, — поэтому
     * набранное сначала записывается, и лечение получает номер (PLAN H3 №15, D5). Названия нет —
     * записать нечего, и отказ встаёт у своего поля, как у всякой другой записи.
     */
    fun openSources() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        if (current.isBusy) return
        current.plan?.let { editing.value = current.copy(sourcesOf = it.id); return }
        current.stored?.let { editing.value = current.copy(sourcesOf = it.id); return }
        val saving = current.copy(error = null, isSaving = true)
        editing.value = saving
        viewModelScope.launch {
            when (val parsed = current.form.parsed(vocabulary.snapshot())) {
                is ParsedInput.Rejected -> editing.value = saving.copy(isSaving = false, error = parsed.error)
                is ParsedInput.Parsed -> {
                    val written = written(saving, parsed.value) ?: return@launch
                    editing.value = saving.copy(isSaving = false, stored = written, sourcesOf = written.id)
                }
            }
        }
    }

    /** Экран источников открыт — второй раз туда же не уводим. */
    fun sourcesOpened() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        editing.value = current.copy(sourcesOf = null)
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

    /**
     * Что открыто — черновик или идущее лечение, — экран узнаёт у базы: у ключа маршрута номер
     * один на оба случая, потому что эпизод один (PLAN H3 №15).
     */
    private suspend fun open(id: Uuid) {
        val draft = courses.observeDrafts().first().firstOrNull { it.id == id }
        if (draft != null) {
            val form = draft.toFormPresentationDTO()
            editing.value = CourseFormUiState.Editing(
                form,
                CourseFormUiState.Mode.DRAFT,
                stored = draft,
                expectedEnd = form.expectedEnd()
            )
            return
        }
        val plan = courses.observePlan(id).first()
        val record = courses.observeRecord(id).first()
        // Ни черновика, ни плана — это отказ, а не пустая форма: заполненную человек сохранил бы
        // и не понял, куда делась его правка.
        editing.value = if (plan != null && record != null) {
            val form = record.toFormPresentationDTO(plan)
            CourseFormUiState.Editing(
                form,
                CourseFormUiState.Mode.RUNNING,
                plan = plan,
                record = record,
                expectedEnd = form.expectedEnd()
            )
        } else {
            CourseFormUiState.Gone
        }
    }

    private suspend fun write(saving: CourseFormUiState.Editing, described: CourseDescription) {
        if (saving.mode == CourseFormUiState.Mode.RUNNING) {
            editing.value = saving.amended(described)
            return
        }
        val written = written(saving, described) ?: return
        editing.value = saving.copy(isSaving = false, isSaved = true, stored = written)
    }

    /**
     * Изменение идущего лечения — **тот же эпизод** (PLAN C1, D5). Название и заметка правятся у
     * записи и назначения не касаются; из назначения уходит только тронутое. Ничего не тронуто —
     * человеку всё равно «записано»: он нажал и ждёт ответа, а не разбора, что именно изменилось.
     */
    private suspend fun CourseFormUiState.Editing.amended(described: CourseDescription): CourseFormUiState {
        val plan = checkNotNull(plan)
        val record = checkNotNull(record)
        if (described.title != record.title || described.note != record.note) {
            if (renaming.rename(plan.id, described.title, described.note) == CourseRenaming.Outcome.GONE) {
                return CourseFormUiState.Gone
            }
        }
        val changes = described.changesSince(plan.prescription)
        if (changes.isEmpty()) return copy(isSaving = false, isSaved = true)
        return told(amendment.amend(plan.id, plan.revision, changes))
    }

    /** Чем кончилось изменение лечения: изменили; лечение этим закончилось; отказ по месту. */
    private fun CourseFormUiState.Editing.told(outcome: CourseAmendment.Outcome): CourseFormUiState = when (outcome) {
        // Закончилось — запись эпизода расскажет об этом карточкой; править больше нечего.
        is CourseAmendment.Outcome.Amended, CourseAmendment.Outcome.Finished ->
            copy(isSaving = false, isSaved = true)
        CourseAmendment.Outcome.AlreadyFinished -> copy(isSaving = false, error = CourseFormError.Finished)
        CourseAmendment.Outcome.Gone -> CourseFormUiState.Gone
        CourseAmendment.Outcome.Stale -> copy(isSaving = false, error = CourseFormError.Stale)
        is CourseAmendment.Outcome.Rejected -> copy(isSaving = false, error = CourseFormError.Rejected(outcome.reason))
    }

    /**
     * Записывает набранное и отдаёт записанный черновик. `null` — записать не вышло, и человеку
     * об этом уже сказано: отказ, устаревшая редакция или пропавший черновик.
     */
    private suspend fun written(
        saving: CourseFormUiState.Editing,
        described: CourseDescription
    ): CourseDraftProjection? {
        val base = when (saving.mode) {
            CourseFormUiState.Mode.NEW_DRAFT -> drafting.create(described.title, described.note)
            CourseFormUiState.Mode.DRAFT -> checkNotNull(saving.stored)
            // Идущее лечение правится изменением эпизода, а не записью черновика: сюда оно не доходит.
            CourseFormUiState.Mode.RUNNING -> error("идущее лечение не записывается черновиком")
        }
        val edits = described.editsSince(base)
        if (edits.isEmpty()) return base
        return when (val outcome = drafting.edit(base.id, base.revision, edits)) {
            is CourseDrafting.Outcome.Saved -> outcome.draft
            else -> {
                editing.value = saving.told(outcome)
                null
            }
        }
    }

    /**
     * Начать лечение (PLAN H3 №15, D5). Сначала записывается набранное — человек нажимает
     * «Начать» с тем назначением, которое видит на экране, — и только потом лечение начинается,
     * по **свежей** редакции записанного черновика.
     *
     * Полноты назначения экран не проверяет: её требует сценарий, и чего не хватает, он называет
     * сам — второй проверки здесь нет (D5 «Черновик»).
     */
    fun start() {
        val current = editing.value as? CourseFormUiState.Editing ?: return
        if (current.isBusy || current.isSaved || current.startedId != null) return
        val starting = current.copy(error = null, isStarting = true)
        editing.value = starting
        viewModelScope.launch {
            when (val parsed = current.form.parsed(vocabulary.snapshot())) {
                is ParsedInput.Rejected -> editing.value = starting.copy(isStarting = false, error = parsed.error)
                is ParsedInput.Parsed -> {
                    val written = written(starting, parsed.value) ?: return@launch
                    editing.value = starting.copy(stored = written).began(activation.activate(written.id, written.revision))
                }
            }
        }
    }

    /** Чем кончилось начало лечения — человеку: пошло; уже идёт; отказ по месту; занятая пачка. */
    private fun CourseFormUiState.Editing.began(outcome: CourseActivation.Outcome): CourseFormUiState = when (outcome) {
        is CourseActivation.Outcome.Started -> copy(isStarting = false, startedId = outcome.course.id)
        // Уже идёт — повтор ничего не менял, и человеку нужна та же карточка.
        CourseActivation.Outcome.AlreadyStarted -> copy(isStarting = false, startedId = stored?.id ?: courseId)
        CourseActivation.Outcome.Gone -> CourseFormUiState.Gone
        CourseActivation.Outcome.Stale -> copy(isStarting = false, error = CourseFormError.Stale)
        is CourseActivation.Outcome.Rejected -> copy(isStarting = false, error = CourseFormError.Rejected(outcome.reason))
        is CourseActivation.Outcome.PackageTaken ->
            copy(isStarting = false, error = CourseFormError.PackageTaken(nameOf(outcome.packageId)))
        is CourseActivation.Outcome.PackageUnusable ->
            copy(isStarting = false, error = CourseFormError.PackageUnusable(nameOf(outcome.packageId)))
    }

    /** Имя коробки — из состава черновика: отказ называет коробку так, как человек её знает. */
    private fun CourseFormUiState.Editing.nameOf(packageId: Uuid): String? =
        stored?.sources?.firstOrNull { it.pkg.id == packageId }?.pkg?.name

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
        /** Записанный черновик, с которым сравнивают при записи: уходит только изменённое. */
        val stored: CourseDraftProjection? = null,
        /** Идущее лечение: назначение живёт в плане, название и заметка — в записи эпизода. */
        val plan: CourseProjection? = null,
        val record: CourseRecordProjection? = null,
        val units: List<UnitPresentationDTO> = emptyList(),
        val forms: List<FormPresentationDTO> = emptyList(),
        /** Когда ожидается последний приём по тому, что набрано; нечего считать — `null`. */
        val expectedEnd: LocalDate? = null,
        val error: CourseFormError? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val isStarting: Boolean = false,
        /** Куда идти за источниками: номер записанного лечения; `null` — идти пока некуда. */
        val sourcesOf: Uuid? = null,
        /** Лечение началось — этот эпизод и открывают карточкой; `null` — ещё черновик. */
        val startedId: Uuid? = null,
        val asksToDiscard: Boolean = false,
        val isDiscarding: Boolean = false,
        val isDiscarded: Boolean = false
    ) : CourseFormUiState {
        val isBusy: Boolean get() = isSaving || isDiscarding || isStarting
    }

    /**
     * Чем открыт редактор: новым черновиком, записанным черновиком или идущим лечением. У
     * идущего те же поля правят **тот же эпизод**, а не заводят новый (PLAN C1 «Изменение
     * лечения»).
     */
    enum class Mode { NEW_DRAFT, DRAFT, RUNNING }
}
