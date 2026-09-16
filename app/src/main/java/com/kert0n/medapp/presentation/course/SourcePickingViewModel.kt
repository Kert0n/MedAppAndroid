package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.doses
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
 * Выбор источника (PLAN H3 №17): какие коробки можно подключить к лечению. Подключается коробка
 * **с нулём приёмов** — сколько из неё брать, человек решает ползунком на экране источников, и
 * два решения в одно нажатие не сливаются.
 *
 * Годится ли коробка, решает назначение, а не экран: сравнение формы и единицы живёт в домене
 * (`Prescription.faultOf`, `CourseSource.Fault.between`), занятость приносит проекция коробки,
 * пригодность — её статус.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = SourcePickingViewModel.Factory::class)
class SourcePickingViewModel @AssistedInject constructor(
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
        fun create(courseId: Uuid): SourcePickingViewModel
    }

    private val days = today.observe().map { it.date }.distinctUntilChanged()

    private val stored = combine(
        courses.observeDrafts().map { drafts -> drafts.firstOrNull { it.id == courseId } },
        courses.observePlan(courseId)
    ) { draft, plan ->
        when {
            draft != null -> Stored(courseId, true, draft.revision, draft.dose, draft.form, draft.sources)
            plan != null -> Stored(
                courseId = courseId,
                isDraft = false,
                revision = plan.revision,
                dose = plan.prescription.dose,
                form = plan.prescription.form,
                sources = plan.sources
            )
            else -> null
        }
    }

    /** `null` — не прочитано **ещё**: нажатие до первого чтения ждёт его, а не пропадает. */
    private val latest = MutableStateFlow<Reading?>(null)

    private val attaching = MutableStateFlow(Attaching())

    private val shelves = days
        .flatMapLatest { date -> medKits.observeAll(date).map { kits -> kits.associate { it.id to it.name } } }

    /** Что человек ищет. Отбор по названию делает хранение — тем же запросом, что и на полке (H4). */
    private val query = MutableStateFlow("")

    private val boxes = combine(days, query) { date, text -> date to text }
        .distinctUntilChanged()
        .flatMapLatest { (date, text) -> packages.list(PackageQuery(text = text), date) }

    /** Чем назван курс, держащий коробку: имя лечения живёт у записи эпизода, а не у плана (D5). */
    private val titles = courses.observeRecords()
        .map { records -> records.filter { it.isOpen }.associate { it.id to it.title } }

    val state: StateFlow<SourcePickingUiState> = combine(
        latest.filterNotNull(),
        boxes,
        shelves,
        titles,
        combine(attaching, query) { attaching, text -> attaching to text }
    ) { reading, boxes, shelves, titles, (attaching, text) ->
        val stored = reading.stored
        if (stored == null) SourcePickingUiState(isGone = true, text = text)
        else SourcePickingUiState(
            packages = boxes.map {
                it.toAttachmentPresentationDTO(shelves[it.medKit.id], stored.attachability(it, titles))
            },
            text = text,
            isAttached = attaching.attached,
            isAttaching = attaching.busy,
            message = attaching.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcePickingUiState(isLoading = true))

    /** Искать по названию: пустая строка — снова все коробки. */
    fun search(text: String) {
        query.value = text
    }

    init {
        viewModelScope.launch { stored.collect { latest.value = Reading(it) } }
    }

    /**
     * Подключить коробку. Второе нажатие, пока идёт первое или пока уже подключено, ничего не
     * делает: сторожем служит само состояние.
     */
    fun attach(packageId: Uuid) {
        val attaching = attaching.value
        if (attaching.busy || attaching.attached) return
        this.attaching.value = attaching.copy(busy = true)
        viewModelScope.launch {
            // Ждём чтение, а не проверяем его наличие: нажатие до первого чтения иначе пропало бы.
            val stored = latest.filterNotNull().first().stored
                ?: return@launch run { this@SourcePickingViewModel.attaching.value = Attaching() }
            val outcome = if (stored.isDraft) {
                told(drafting.edit(courseId, stored.revision, listOf(CourseDrafting.Edit.Attach(packageId, 0.doses))))
            } else {
                val wanted = stored.sources.map { SourceEditing.Source(it.pkg.id, it.allocatedDoses) } +
                    SourceEditing.Source(packageId, 0.doses)
                told(sources.save(courseId, stored.revision, wanted))
            }
            this@SourcePickingViewModel.attaching.value = outcome
        }
    }

    fun dismissMessage() {
        attaching.value = attaching.value.copy(message = null)
    }

    private fun told(outcome: CourseDrafting.Outcome): Attaching = when (outcome) {
        is CourseDrafting.Outcome.Saved -> Attaching(attached = true)
        // Черновика нет или он устарел: показывать нечего — экран уходит, а список перечитается.
        CourseDrafting.Outcome.Gone, CourseDrafting.Outcome.Stale -> Attaching(attached = true)
        is CourseDrafting.Outcome.Rejected -> Attaching(message = CourseSourcesMessage.Refused(outcome.reason))
        CourseDrafting.Outcome.PackageUnusable -> Attaching(message = CourseSourcesMessage.Unusable(null))
    }

    private fun told(outcome: SourceEditing.Outcome): Attaching = when (outcome) {
        is SourceEditing.Outcome.Saved, SourceEditing.Outcome.Gone, SourceEditing.Outcome.Stale ->
            Attaching(attached = true)
        SourceEditing.Outcome.AlreadyFinished -> Attaching(message = CourseSourcesMessage.Finished)
        is SourceEditing.Outcome.Rejected -> Attaching(message = CourseSourcesMessage.Refused(outcome.reason))
        is SourceEditing.Outcome.PackageTaken -> Attaching(message = CourseSourcesMessage.Taken(null))
        is SourceEditing.Outcome.PackageUnusable -> Attaching(message = CourseSourcesMessage.Unusable(null))
        is SourceEditing.Outcome.BeyondLimit ->
            Attaching(message = CourseSourcesMessage.BeyondLimit(null, outcome.limit.count))
    }

    private data class Stored(
        val courseId: Uuid,
        val isDraft: Boolean,
        val revision: Revision,
        val dose: Dose?,
        val form: DosageForm?,
        val sources: List<CourseSource>
    ) {

        /**
         * Можно ли взять эту коробку, и если нет — почему. Порядок вопросов тот же, каким
         * отвечает подключение: непригодную не берут вовсе, уже взятую не берут второй раз, без
         * формы не с чем сверять, а дальше решает правило домена (PLAN D5).
         *
         * Случай «у коробки не указана форма» экран узнаёт сам: домен различает его внутри
         * `attach` (`FORM_UNKNOWN`), а снаружи такого вопроса не задаёт.
         */
        fun attachability(pack: PackageProjection, titles: Map<Uuid, String>): Attachability {
            val dose = dose ?: return Attachability.PrescriptionIncomplete
            val form = form ?: return Attachability.PrescriptionIncomplete
            if (!pack.status.allowsUse) return Attachability.Unusable
            if (sources.any { it.pkg.id == pack.id }) return Attachability.Attached
            pack.holdingCourseId?.takeIf { it != courseId }?.let {
                return Attachability.HeldByCourse(titles[it])
            }
            if (pack.ref.form == null) return Attachability.NeedsForm
            return CourseSource.Fault.between(pack.ref, dose, form)
                ?.let { Attachability.Mismatch(it) }
                ?: Attachability.Attachable
        }
    }

    private data class Attaching(
        val busy: Boolean = false,
        val attached: Boolean = false,
        val message: CourseSourcesMessage? = null
    )

    /** Чтение, которое уже случилось: [stored] `null` — лечения нет, а не «ещё не читали». */
    private data class Reading(val stored: Stored?)
}

/**
 * Что показывает выбор источника. [isAttached] — коробка подключена, и экран уходит: выделять из
 * неё приёмы человек будет там, где виден весь стек.
 */
data class SourcePickingUiState(
    val packages: List<PackageAttachmentPresentationDTO> = emptyList(),
    /** Что набрано в поиске: пусто — показаны все коробки. */
    val text: String = "",
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val isAttaching: Boolean = false,
    val isAttached: Boolean = false,
    val message: CourseSourcesMessage? = null
)
