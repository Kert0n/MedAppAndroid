package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.feature.course.CourseCancellation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Карточка лечения (PLAN H3 №14). Первым — **обеспечение**: человек открывает её, чтобы узнать,
 * хватит ли лекарства; назначение и пункты он читает вторым взглядом.
 *
 * Идущее и законченное лечение — одна карточка: у записи эпизода одна форма, и разными их делает
 * исход, а не экран (D5). У закрытого плана нет — ни обеспечения, ни источников, ни действий.
 */
@HiltViewModel(assistedFactory = CourseCardViewModel.Factory::class)
class CourseCardViewModel @AssistedInject constructor(
    private val cancellation: CourseCancellation,
    courses: CourseStorageRepository,
    intakes: IntakeStorageRepository,
    @Assisted private val courseId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Uuid): CourseCardViewModel
    }

    private val cancelling = MutableStateFlow(Cancelling())

    val state: StateFlow<CourseCardUiState> = combine(
        courses.observeRecord(courseId),
        courses.observeCoverage(courseId),
        courses.observeReductions(courseId),
        intakes.observeOfCourse(courseId),
        cancelling
    ) { record, coverage, reductions, intakes, cancelling ->
        if (record == null) CourseCardUiState(isGone = true)
        else record.toCardUiState(coverage, reductions, intakes, cancelling)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseCardUiState(isLoading = true))

    /** Отмена спрашивается: будущие приёмы уйдут, а пачки освободятся (H3, список подтверждений). */
    fun askToCancel() {
        if (cancelling.value.working) return
        cancelling.value = Cancelling(asking = true)
    }

    fun dismissCancel() {
        if (cancelling.value.working) return
        cancelling.value = Cancelling()
    }

    /**
     * Отменить лечение. Зовётся после ответа человека; второе нажатие ничего не начинает.
     * Уже законченного отменять нечего — карточка просто показывает исход, который у него есть.
     */
    fun cancel() {
        val now = cancelling.value
        if (!now.asking || now.working) return
        cancelling.value = now.copy(asking = false, working = true)
        viewModelScope.launch {
            cancellation.cancel(courseId)
            cancelling.value = Cancelling()
        }
    }

    private fun CourseRecordProjection.toCardUiState(
        coverage: CourseCoverage?,
        reductions: List<CoverageReduction>,
        intakes: List<IntakeProjection>,
        cancelling: Cancelling
    ): CourseCardUiState {
        val zone = prescription.schedule.zone
        return CourseCardUiState(
            course = toPresentationDTO(coverage),
            coverage = coverage?.takeIf { isOpen }?.toPresentationDTO(),
            reductions = reductions.map { it.toPresentationDTO(zone, intakes.packageNames()) },
            items = intakes.filterIsInstance<IntakeProjection.Scheduled>().map { it.toPresentationDTO(zone) },
            isRunning = isOpen,
            asksToCancel = cancelling.asking,
            isCancelling = cancelling.working
        )
    }

    /** Имена коробок берутся у приёмов: сокращение помнит номер, а имя — та ссылка, что рядом. */
    private fun List<IntakeProjection>.packageNames(): Map<Uuid, String> = buildMap {
        for (intake in this@packageNames) {
            val scheduled = intake as? IntakeProjection.Scheduled
            scheduled?.plannedPackage?.let { put(it.id, it.name) }
            intake.taken?.pkg?.let { put(it.id, it.name) }
        }
    }

    private data class Cancelling(val asking: Boolean = false, val working: Boolean = false)
}

/**
 * Что показывает карточка. [isGone] — эпизода нет вовсе; [isRunning] отличает идущее лечение от
 * законченного: у второго нет ни обеспечения, ни действий, а история остаётся.
 */
data class CourseCardUiState(
    val course: CoursePresentationDTO? = null,
    val coverage: CourseCoveragePresentationDTO? = null,
    val reductions: List<CoverageReductionPresentationDTO> = emptyList(),
    val items: List<CourseItemPresentationDTO> = emptyList(),
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val asksToCancel: Boolean = false,
    val isCancelling: Boolean = false
)
