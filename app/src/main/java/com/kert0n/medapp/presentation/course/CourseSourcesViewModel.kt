package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.time.Today
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
import kotlinx.coroutines.channels.Channel
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
 * Источники лечения (PLAN H3 №16): стек коробок, из которых оно берётся, в порядке расходования.
 *
 * **Кнопки «Сохранить» у экрана нет**: человек отпустил ползунок или бросил строку — состав
 * записан, и новое чтение само приносит обеспечение и новые пределы. Своего состава экран не
 * держит — только намерение, пока оно едет в сценарий. Записи идут **по одной**, в том порядке, в
 * каком человек их сделал: иначе вторая ушла бы с редакцией, которую первая уже сдвинула.
 * Устаревшая редакция человеку не показывается — он своего не терял, и намерение повторяется по
 * перечитанному составу.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CourseSourcesViewModel.Factory::class)
class CourseSourcesViewModel @AssistedInject constructor(
    private val drafting: CourseDrafting,
    private val sources: SourceEditing,
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
            draft != null -> Stored(draft.title, true, draft.revision, draft.dose, draft.sources, null)
            plan != null -> Stored(
                title = record?.title,
                isDraft = false,
                revision = plan.revision,
                dose = plan.prescription.dose,
                sources = plan.sources,
                coverage = coverage,
                isFinished = record?.isOpen == false
            )
            else -> null
        }
    }

    /**
     * Единственное чтение лечения: из него и строки на экране, и то, с чем сверяется запись.
     * Две подписки на одно и то же расходятся во времени — показанное человеку и записанное
     * оказались бы разными составами. `null` — не прочитано **ещё**, и это не «лечения нет»:
     * намерение, сделанное до первого чтения, ждёт его, а не пропадает.
     */
    private val latest = MutableStateFlow<Reading?>(null)

    private val intents = Channel<Intent>(Channel.UNLIMITED)

    private val writing = MutableStateFlow(Writing())

    private val shelves = days
        .flatMapLatest { date -> medKits.observeAll(date).map { kits -> kits.associate { it.id to it.name } } }

    private val boxes = days
        .flatMapLatest { date -> packages.list(PackageQuery(), date).map { list -> list.associateBy { it.id } } }

    val state: StateFlow<CourseSourcesUiState> = combine(
        latest.filterNotNull(),
        boxes,
        shelves,
        writing
    ) { reading, boxes, shelves, writing ->
        val stored = reading.stored
        // Чтение пришло, а лечения в нём нет — его больше не существует; до первого чтения сюда
        // не доходят вовсе, и «нет» вместо ожидания не показывается.
        if (stored == null) CourseSourcesUiState(isGone = true)
        else CourseSourcesUiState(
            title = stored.title,
            isDraft = stored.isDraft,
            isFinished = stored.isFinished,
            sources = stored.sources.map { source ->
                val pack = boxes[source.pkg.id]
                source.toPresentationDTO(
                    dose = stored.dose,
                    pack = pack,
                    medKitName = pack?.let { shelves[it.medKit.id] },
                    covered = stored.coverage?.perSource?.firstOrNull { it.pkg == source.pkg }
                )
            },
            coverage = stored.coverage?.toPresentationDTO(),
            isWriting = writing.busy,
            asksToDetach = writing.asksToDetach,
            message = writing.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseSourcesUiState(isLoading = true))

    init {
        // Одно чтение на экран и на запись, и живёт оно, пока жив экран: подписка на время
        // показа оставила бы запись без того состава, по которому человек её сделал.
        viewModelScope.launch { stored.collect { latest.value = Reading(it) } }
        viewModelScope.launch {
            for (intent in intents) {
                writing.value = writing.value.copy(busy = true)
                apply(intent)
                writing.value = writing.value.copy(busy = false)
            }
        }
    }

    /**
     * Выделить коробке столько приёмов, сколько человек отпустил на ползунке или дописал в поле.
     * Число зажимается к пределу строки: выше него оно всё равно не запишется, а зажатое видно
     * сразу (PLAN D5, C1 «Ползунок»). Ползунок в движении сюда не заходит — только отпущенный.
     */
    fun allocate(packageId: Uuid, doses: Int) {
        val source = state.value.sources.firstOrNull { it.packageId == packageId } ?: return
        if (source.fault != null) return
        val wanted = doses.coerceIn(0, source.maxDoses ?: doses)
        if (wanted == source.allocatedDoses) return
        intents.trySend(Intent.Allocate(packageId, Doses(wanted)))
    }

    /** Переставить источник: место в списке — очередь расходования (PLAN D5). */
    fun move(from: Int, to: Int) {
        val count = state.value.sources.size
        if (from == to || from !in 0 until count || to !in 0 until count) return
        intents.trySend(Intent.Move(from, to))
    }

    /**
     * Отвязка у идущего лечения спрашивается: коробка освободится, и её бронь снимется (H3).
     * У черновика спрашивать нечего — он ничего не занимал.
     */
    fun askToDetach(packageId: Uuid) {
        if (state.value.isDraft) intents.trySend(Intent.Detach(packageId))
        else writing.value = writing.value.copy(asksToDetach = packageId)
    }

    fun dismissDetach() {
        writing.value = writing.value.copy(asksToDetach = null)
    }

    fun detach() {
        val packageId = writing.value.asksToDetach ?: return
        writing.value = writing.value.copy(asksToDetach = null)
        intents.trySend(Intent.Detach(packageId))
    }

    fun dismissMessage() {
        writing.value = writing.value.copy(message = null)
    }

    /**
     * Записывает намерение по последнему прочитанному составу. Состав правят с другого экрана или
     * следом за коробкой — редакция уходит вперёд; тогда намерение повторяется по новому чтению.
     */
    private suspend fun apply(intent: Intent) {
        repeat(ATTEMPTS) {
            // Ждём чтение, а не проверяем его наличие: под нагрузкой первое значение приходит
            // позже нажатия, и брошенное намерение пропало бы молча.
            val stored = latest.filterNotNull().first().stored ?: return
            val wanted = intent.appliedTo(stored.sources) ?: return
            val written = if (stored.isDraft) {
                told(drafting.edit(courseId, stored.revision, intent.asEdits()))
            } else {
                told(sources.save(courseId, stored.revision, wanted.map { SourceEditing.Source(it.pkg.id, it.allocatedDoses) }))
            }
            if (written != Written.STALE) return
            // Ждём чтение новее того, по которому писали: без него повтор уйдёт с той же редакцией.
            latest.first { it?.stored != null && it.stored.revision != stored.revision }
        }
    }

    private fun told(outcome: CourseDrafting.Outcome): Written = when (outcome) {
        // Черновика нет — писать некуда, и экран уже говорит об этом своим чтением.
        is CourseDrafting.Outcome.Saved, CourseDrafting.Outcome.Gone -> Written.DONE
        CourseDrafting.Outcome.Stale -> Written.STALE
        is CourseDrafting.Outcome.Rejected -> refused(CourseSourcesMessage.Refused(outcome.reason))
        CourseDrafting.Outcome.PackageUnusable -> refused(CourseSourcesMessage.Unusable(null))
    }

    private fun told(outcome: SourceEditing.Outcome): Written = when (outcome) {
        is SourceEditing.Outcome.Saved, SourceEditing.Outcome.Gone -> Written.DONE
        SourceEditing.Outcome.Stale -> Written.STALE
        SourceEditing.Outcome.AlreadyFinished -> refused(CourseSourcesMessage.Finished)
        is SourceEditing.Outcome.Rejected -> refused(CourseSourcesMessage.Refused(outcome.reason))
        is SourceEditing.Outcome.PackageTaken -> refused(CourseSourcesMessage.Taken(nameOf(outcome.packageId)))
        is SourceEditing.Outcome.PackageUnusable -> refused(CourseSourcesMessage.Unusable(nameOf(outcome.packageId)))
        is SourceEditing.Outcome.BeyondLimit ->
            refused(CourseSourcesMessage.BeyondLimit(nameOf(outcome.packageId), outcome.limit.count))
    }

    private fun refused(message: CourseSourcesMessage): Written {
        writing.value = writing.value.copy(message = message)
        return Written.DONE
    }

    private fun nameOf(packageId: Uuid): String? =
        latest.value?.stored?.sources?.firstOrNull { it.pkg.id == packageId }?.pkg?.name

    /** Чтение, которое уже случилось: [stored] `null` — лечения нет, а не «ещё не читали». */
    private data class Reading(val stored: Stored?)

    private enum class Written { DONE, STALE }

    /** Что человек сделал со стеком: намерение, а не готовый состав — состав считает прочитанное. */
    private sealed interface Intent {

        data class Move(val from: Int, val to: Int) : Intent

        data class Detach(val packageId: Uuid) : Intent

        data class Allocate(val packageId: Uuid, val doses: Doses) : Intent

        /** Состав после намерения; `null` — применять уже не к чему. */
        fun appliedTo(sources: List<CourseSource>): List<CourseSource>? = when (this) {
            is Move ->
                if (from !in sources.indices || to !in sources.indices) null
                else sources.toMutableList().apply { add(to, removeAt(from)) }
            is Detach -> sources.filterNot { it.pkg.id == packageId }.takeIf { it.size != sources.size }
            is Allocate -> sources
                .map { if (it.pkg.id == packageId) it.copy(allocatedDoses = doses) else it }
                .takeIf { it != sources }
        }

        /** То же намерение словами черновика: он правится названными действиями, а не составом. */
        fun asEdits(): List<CourseDrafting.Edit> = when (this) {
            is Move -> listOf(CourseDrafting.Edit.Reorder(from, to))
            is Detach -> listOf(CourseDrafting.Edit.Detach(packageId))
            is Allocate -> listOf(CourseDrafting.Edit.Allocate(packageId, doses))
        }
    }

    private data class Stored(
        val title: String?,
        val isDraft: Boolean,
        val revision: Revision,
        val dose: Dose?,
        val sources: List<CourseSource>,
        val coverage: CourseCoverage?,
        val isFinished: Boolean = false
    )

    private data class Writing(
        val busy: Boolean = false,
        val asksToDetach: Uuid? = null,
        val message: CourseSourcesMessage? = null
    )

    private companion object {
        /** Повторов записи по перечитанному составу: столько правок подряд с другого экрана — уже не гонка. */
        const val ATTEMPTS = 3
    }
}

/**
 * Что показывает экран источников. [isGone] и [isFinished] различаются тем, что делает человек:
 * первое — лечения больше нет, второе — оно закончено, и источников у него уже не бывает.
 */
data class CourseSourcesUiState(
    val title: String? = null,
    val sources: List<CourseSourcePresentationDTO> = emptyList(),
    /** Чем лечение обеспечено; `null` — у черновика: считать обеспечение ему не по чему (B15). */
    val coverage: CourseCoveragePresentationDTO? = null,
    val isDraft: Boolean = false,
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val isFinished: Boolean = false,
    val isWriting: Boolean = false,
    /** Какую коробку человек собрался отвязать у идущего лечения; `null` — вопроса нет. */
    val asksToDetach: Uuid? = null,
    val message: CourseSourcesMessage? = null
)
