package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.course.SourceEstimates
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Источники лечения (PLAN H3 №16): стек коробок в порядке расходования.
 *
 * **Правка местная, записывает её «Сохранить»** (решение владельца 2026-09-16). Ползунок двигают
 * пальцем, и ждать между движениями базу нельзя: предел строки считает домен по составу, который
 * человек собрал здесь, — вопросом через `SourceEstimates`, без транзакции и записи. Одно решение
 * человека — один вызов сценария, и до него не уходит ничего.
 *
 * Чтение одно на экран и на запись: две подписки на одно и то же расходятся во времени, и
 * показанное человеку оказалось бы не тем составом, который ушёл в сценарий. Пока правка не
 * записана, чужое изменение состава её сбрасывает — писать поверх чужого вслепую нельзя (F5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CourseSourcesViewModel.Factory::class)
class CourseSourcesViewModel @AssistedInject constructor(
    private val drafting: CourseDrafting,
    private val sources: SourceEditing,
    private val estimates: SourceEstimates,
    courses: CourseStorageRepository,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    @Assisted private val courseId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Uuid): CourseSourcesViewModel
    }

    private val days = today.observe().map { it.date }.distinctUntilChanged()

    private val stored = combine(
        courses.observeDrafts().map { drafts -> drafts.firstOrNull { it.id == courseId } },
        courses.observePlan(courseId),
        courses.observeRecord(courseId),
        courses.observeCoverage(courseId)
    ) { draft, plan, record, coverage ->
        when {
            draft != null -> Stored(
                title = draft.title,
                isDraft = true,
                revision = draft.revision,
                dose = draft.dose,
                sources = draft.sources,
                coverage = null,
                requiredDoses = draft.totalDoses?.count
            )
            plan != null -> Stored(
                title = record?.title,
                isDraft = false,
                revision = plan.revision,
                dose = plan.prescription.dose,
                sources = plan.sources,
                coverage = coverage,
                requiredDoses = coverage?.requiredDoses?.count,
                isFinished = record?.isOpen == false
            )
            else -> null
        }
    }

    /** Последнее чтение: из него и строки экрана, и то, с чем сверяется запись. */
    private val latest = MutableStateFlow<Reading?>(null)

    /** Правка человека до «Сохранить»; `null` — он ещё ничего не трогал. */
    private val editing = MutableStateFlow<Editing?>(null)

    private val writing = MutableStateFlow(Writing())

    private val shelves = days
        .flatMapLatest { date -> medKits.observeAll(date).map { kits -> kits.associate { it.id to it.name } } }

    private val boxes = days
        .flatMapLatest { date -> packages.list(PackageQuery(), date).map { list -> list.associateBy { it.id } } }

    val state: StateFlow<CourseSourcesUiState> = combine(
        latest.filterNotNull(),
        editing,
        boxes,
        shelves,
        writing
    ) { reading, editing, boxes, shelves, writing ->
        val stored = reading.stored
        if (stored == null) CourseSourcesUiState(isGone = true)
        else {
            val shown = editing?.sources ?: stored.sources
            val estimate = stored.estimate(shown, boxes)
            CourseSourcesUiState(
                title = stored.title,
                // Доза — мера, которой меряют строки: без неё «целой дозы не наберётся» просит
                // человека считать в уме (H3 №16).
                dose = stored.dose?.quantity?.toPresentationDTO(),
                isDraft = stored.isDraft,
                isFinished = stored.isFinished,
                sources = stored.rows(shown, boxes, shelves, estimate),
                // Записанное обеспечение считает база: у него есть и день, с которого не хватает.
                coverage = stored.coverage?.toPresentationDTO(),
                // Что получится у собранного состава — пока не записано, это единственный честный
                // итог: сколько приёмов обеспечено и скольких не хватает.
                estimate = estimate?.let {
                    CourseEstimatePresentationDTO(
                        requiredDoses = it.requiredDoses.count,
                        coveredDoses = it.coveredDoses.count,
                        missingDoses = it.missingDoses.count
                    )
                },
                hasUnsavedChanges = shown != stored.sources,
                isWriting = writing.busy,
                asksToDetach = writing.asksToDetach,
                message = writing.message
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseSourcesUiState(isLoading = true))

    init {
        viewModelScope.launch {
            stored.collect { fresh ->
                latest.value = Reading(fresh)
                // Состав сменился — правка начинается заново: она была о прежнем составе, и
                // после нашей же записи, и после чужой. **Вопрос уходит вместе с ней**: он был о
                // том же прежнем составе, а у последней коробки ответ записывает сразу — и снял
                // бы не то, о чём спрашивали.
                val started = editing.value
                if (fresh == null || started == null || started.revision != fresh.revision) {
                    editing.value = fresh?.let { Editing(it.revision, it.sources) }
                    writing.value = writing.value.copy(asksToDetach = null)
                }
            }
        }
    }

    /**
     * Выделить коробке приёмы. Число зажимается к пределу строки — тому же, что показан рядом, —
     * и никуда не уходит: движение пальца базу не трогает.
     */
    fun allocate(packageId: Uuid, doses: Int) {
        val editing = editing.value ?: return
        val source = state.value.sources.firstOrNull { it.packageId == packageId } ?: return
        if (source.fault != null) return
        val wanted = doses.coerceIn(0, source.maxDoses ?: doses)
        this.editing.value = editing.copy(
            sources = editing.sources.map {
                if (it.pkg.id == packageId) it.copy(allocatedDoses = Doses(wanted)) else it
            }
        )
    }

    /** Переставить источник: место в списке — очередь расходования (PLAN D5). */
    fun move(from: Int, to: Int) {
        val editing = editing.value ?: return
        if (from == to || from !in editing.sources.indices || to !in editing.sources.indices) return
        this.editing.value = editing.copy(
            sources = editing.sources.toMutableList().apply { add(to, removeAt(from)) }
        )
    }

    /**
     * Отвязка у идущего лечения спрашивается: записанная, она освободит коробку и снимет бронь
     * (H3). У черновика спрашивать нечего — он ничего не занимал.
     *
     * **Последняя коробка — случай особый**, и спрашивают о ней всегда, даже у черновика: после
     * неё лечению нечем обеспечиваться, а ответ и есть запись ([detach]).
     */
    fun askToDetach(packageId: Uuid) {
        val shown = editing.value?.sources.orEmpty()
        val isLast = shown.size == 1 && shown.single().pkg.id == packageId
        if (state.value.isDraft && !isLast) detach(packageId)
        else writing.value = writing.value.copy(asksToDetach = Detaching(packageId, isLast))
    }

    fun dismissDetach() {
        writing.value = writing.value.copy(asksToDetach = null)
    }

    /**
     * Ответ на вопрос. У последней коробки он же и записывает: «Сохранить» живёт **в списке**, а
     * списка после неё не останется — правку стало бы нечем записать, и отвязать последний
     * препарат было бы нельзя вовсе (дефект, найденный владельцем 2026-09-22).
     */
    fun detach() {
        val asked = writing.value.asksToDetach ?: return
        writing.value = writing.value.copy(asksToDetach = null)
        detach(asked.packageId)
        if (asked.isLast) save()
    }

    private fun detach(packageId: Uuid) {
        val editing = editing.value ?: return
        this.editing.value = editing.copy(sources = editing.sources.filterNot { it.pkg.id == packageId })
    }

    fun dismissMessage() {
        writing.value = writing.value.copy(message = null)
    }

    /**
     * Записать состав — одним решением и одним вызовом сценария. Второе нажатие, пока идёт первое,
     * ничего не начинает: сторожем служит само состояние.
     */
    fun save() {
        if (writing.value.busy) return
        writing.value = writing.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val reading = latest.filterNotNull().first()
            val stored = reading.stored
            val wanted = editing.value?.sources
            if (stored == null || wanted == null || wanted == stored.sources) {
                writing.value = writing.value.copy(busy = false)
                return@launch
            }
            val outcome = if (stored.isDraft) {
                told(drafting.edit(courseId, stored.revision, editsFrom(stored.sources, wanted)))
            } else {
                told(sources.save(courseId, stored.revision, wanted.map { SourceEditing.Source(it.pkg.id, it.allocatedDoses) }))
            }
            writing.value = writing.value.copy(busy = false, message = outcome)
        }
    }

    /**
     * Та же правка словами черновика: он правится названными действиями, а не готовым составом.
     * Снятое — отвязкой, изменённое выделение — выделением, порядок — перестановками к целевому
     * месту, по одной за шаг.
     */
    private fun editsFrom(before: List<CourseSource>, after: List<CourseSource>): List<CourseDrafting.Edit> = buildList {
        val wanted = after.map { it.pkg.id }.toSet()
        val working = before.toMutableList()
        for (source in before) {
            if (source.pkg.id !in wanted) {
                add(CourseDrafting.Edit.Detach(source.pkg.id))
                working.removeAll { it.pkg.id == source.pkg.id }
            }
        }
        for (source in after) {
            val had = before.firstOrNull { it.pkg.id == source.pkg.id } ?: continue
            if (had.allocatedDoses != source.allocatedDoses) {
                add(CourseDrafting.Edit.Allocate(source.pkg.id, source.allocatedDoses))
            }
        }
        for ((target, source) in after.withIndex()) {
            val current = working.indexOfFirst { it.pkg.id == source.pkg.id }
            if (current < 0 || current == target) continue
            add(CourseDrafting.Edit.Reorder(current, target))
            working.add(target, working.removeAt(current))
        }
    }

    private fun told(outcome: CourseDrafting.Outcome): CourseSourcesMessage? = when (outcome) {
        is CourseDrafting.Outcome.Saved, CourseDrafting.Outcome.Gone -> null
        CourseDrafting.Outcome.Stale -> CourseSourcesMessage.Stale
        is CourseDrafting.Outcome.Rejected -> CourseSourcesMessage.Refused(outcome.reason)
        is CourseDrafting.Outcome.BeyondLimit ->
            CourseSourcesMessage.BeyondLimit(nameOf(outcome.packageId), outcome.limit.count)
        CourseDrafting.Outcome.PackageUnusable -> CourseSourcesMessage.Unusable(null)
    }

    private fun told(outcome: SourceEditing.Outcome): CourseSourcesMessage? = when (outcome) {
        is SourceEditing.Outcome.Saved, SourceEditing.Outcome.Gone -> null
        SourceEditing.Outcome.Stale -> CourseSourcesMessage.Stale
        SourceEditing.Outcome.AlreadyFinished -> CourseSourcesMessage.Finished
        is SourceEditing.Outcome.Rejected -> CourseSourcesMessage.Refused(outcome.reason)
        is SourceEditing.Outcome.PackageTaken -> CourseSourcesMessage.Taken(nameOf(outcome.packageId))
        is SourceEditing.Outcome.PackageUnusable -> CourseSourcesMessage.Unusable(nameOf(outcome.packageId))
        is SourceEditing.Outcome.BeyondLimit ->
            CourseSourcesMessage.BeyondLimit(nameOf(outcome.packageId), outcome.limit.count)
    }

    private fun nameOf(packageId: Uuid): String? =
        latest.value?.stored?.sources?.firstOrNull { it.pkg.id == packageId }?.pkg?.name

    /**
     * Что получится у собранного состава — вопросом к домену через `feature`: без транзакции и
     * без ожидания базы. Нечего считать, пока не названы доза и число приёмов.
     */
    private fun Stored.estimate(
        shown: List<CourseSource>,
        boxes: Map<Uuid, PackageProjection>
    ): SourceEstimates.Estimate? {
        if (dose == null || requiredDoses == null) return null
        return estimates.of(
            sources = shown,
            dose = dose,
            required = Doses(requiredDoses),
            availableToMe = shown.mapNotNull { source ->
                boxes[source.pkg.id]?.let { source.pkg.id to it.availability.availableToMe }
            }.toMap()
        )
    }

    /** Строки экрана по нынешней правке: пределы считаются от неё, а не от записанного состава. */
    private fun Stored.rows(
        shown: List<CourseSource>,
        boxes: Map<Uuid, PackageProjection>,
        shelves: Map<Uuid, String>,
        estimate: SourceEstimates.Estimate?
    ): List<CourseSourcePresentationDTO> = shown.map { source ->
        val pack = boxes[source.pkg.id]
        source.toPresentationDTO(
            dose = dose,
            pack = pack,
            medKitName = pack?.let { shelves[it.medKit.id] },
            covered = coverage?.perSource?.firstOrNull { it.pkg == source.pkg },
            maxDoses = estimate?.limits?.get(source.pkg.id)?.count
        )
    }

    private data class Stored(
        val title: String?,
        val isDraft: Boolean,
        val revision: Revision,
        val dose: Dose?,
        val sources: List<CourseSource>,
        val coverage: CourseCoverage?,
        /** Сколько приёмов ещё нужно: у идущего — из обеспечения, у черновика — назначенное число. */
        val requiredDoses: Int?,
        val isFinished: Boolean = false
    )

    /** Правка человека: состав в том виде, в каком он его собрал, и редакция, от которой начал. */
    private data class Editing(val revision: Revision, val sources: List<CourseSource>)

    private data class Reading(val stored: Stored?)

    private data class Writing(
        val busy: Boolean = false,
        val asksToDetach: Detaching? = null,
        val message: CourseSourcesMessage? = null
    )
}

/**
 * Что показывает экран источников. [hasUnsavedChanges] — правка есть, но не записана: сводка
 * обеспечения тогда говорит о записанном составе, и экран признаёт это словами.
 */
/**
 * Вопрос об отвязке. Признак живёт **в вопросе**, а не рядом с ним: цена ответа у последней
 * коробки другая — лечение останется ни с чем, — и поведение различает ровно эти два случая.
 */
data class Detaching(val packageId: Uuid, val isLast: Boolean)

data class CourseSourcesUiState(
    val title: String? = null,
    /** Чем меряют выделения: одна доза лечения. `null` — у черновика её ещё не назвали. */
    val dose: QuantityPresentationDTO? = null,
    val sources: List<CourseSourcePresentationDTO> = emptyList(),
    val coverage: CourseCoveragePresentationDTO? = null,
    /** Что получится у собранного состава: считается на месте, без записи (H3 №16). */
    val estimate: CourseEstimatePresentationDTO? = null,
    val isDraft: Boolean = false,
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val isFinished: Boolean = false,
    val isWriting: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    /** Что человек собрался отвязать; `null` — вопроса нет. */
    val asksToDetach: Detaching? = null,
    val message: CourseSourcesMessage? = null
)
