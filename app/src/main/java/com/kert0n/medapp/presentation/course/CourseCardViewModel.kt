package com.kert0n.medapp.presentation.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.feature.course.CourseCancellation
import com.kert0n.medapp.feature.course.CourseOffPlanCounting
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
    private val offPlanCounting: CourseOffPlanCounting,
    courses: CourseStorageRepository,
    intakes: IntakeStorageRepository,
    @Assisted private val courseId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(courseId: Uuid): CourseCardViewModel
    }

    private val cancelling = MutableStateFlow(Cancelling())

    private val counting = MutableStateFlow(Counting())

    /** Редакция плана, которую видел экран: по ней сценарий и узнаёт, что правят виденное (F5). */
    private var revision: Revision? = null

    /** Эпизод — запись и план вместе: имя живёт у записи, состав пачек — у плана (PLAN D5). */
    private val episode = combine(courses.observeRecord(courseId), courses.observePlan(courseId)) { record, plan ->
        record to plan
    }

    val state: StateFlow<CourseCardUiState> = combine(
        episode,
        courses.observeCoverage(courseId),
        courses.observeReductions(courseId),
        intakes.observeOfCourse(courseId),
        combine(cancelling, counting) { cancelling, counting -> cancelling to counting }
    ) { (record, plan), coverage, reductions, intakes, working ->
        revision = plan?.revision
        if (record == null) CourseCardUiState(isGone = true)
        else record.toCardUiState(plan, coverage, reductions, intakes, working.first, working.second)
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
            cancelling.value = told(cancellation.cancel(courseId))
        }
    }

    /**
     * Принял мимо плана. Это **поправка к счёту**, а не приём: расписание и пункты не трогаются,
     * а лечение считает, что доз принято больше (PLAN D5). Спрашивается подтверждением — число
     * уедет в прогресс и может закончить лечение.
     */
    fun askToCountOffPlan() {
        if (counting.value.working) return
        counting.value = Counting(asking = true)
    }

    fun dismissOffPlan() {
        if (counting.value.working) return
        counting.value = Counting()
    }

    /** Записать новый счёт доз мимо плана; второе нажатие ничего не начинает. */
    fun countOffPlan(total: Int) {
        val now = counting.value
        val revision = revision ?: return
        if (!now.asking || now.working) return
        counting.value = now.copy(asking = false, working = true)
        viewModelScope.launch {
            counting.value = told(offPlanCounting.set(courseId, revision, Doses(total)))
        }
    }

    /** Прочитанное сообщение человек уносит сам: до ответа оно остаётся на экране. */
    fun dismissMessage() {
        cancelling.value = cancelling.value.copy(message = null)
    }

    /**
     * Чем кончилась отмена. Два исхода говорит сама карточка, и своих слов им не нужно:
     * отменённое приходит чтением плашкой «Отменён …» и пустым списком источников, а пропавшее —
     * тем же чтением, из которого сценарий и узнал о пропаже: `record == null` делает карточку
     * экраном «Этого лечения больше нет».
     *
     * Своих слов просит только третий: лечение кончилось само, пока человек шёл сюда. Карточка на
     * нём не меняется ничем — она и так показывала «Завершён», — и молчание читалось бы как
     * «ничего не произошло» (H3 §14).
     */
    private fun told(outcome: CourseCancellation.Outcome): Cancelling = when (outcome) {
        CourseCancellation.Outcome.CANCELLED, CourseCancellation.Outcome.GONE -> Cancelling()
        CourseCancellation.Outcome.ALREADY_FINISHED -> Cancelling(message = CourseCardMessage.AlreadyFinished)
    }

    private fun CourseRecordProjection.toCardUiState(
        plan: CourseProjection?,
        coverage: CourseCoverage?,
        reductions: List<CoverageReduction>,
        intakes: List<IntakeProjection>,
        cancelling: Cancelling,
        counting: Counting
    ): CourseCardUiState {
        val zone = prescription.schedule.zone
        return CourseCardUiState(
            course = toPresentationDTO(coverage),
            coverage = coverage?.takeIf { isOpen }?.toPresentationDTO(),
            reductions = reductions.map { it.toPresentationDTO(zone, intakes.packageNames()) },
            // Источники — коротко: чем лечение обеспечивают и сколько из каждой коробки взято.
            sources = plan?.sources.orEmpty().map { source ->
                source.toPresentationDTO(
                    dose = prescription.dose,
                    pack = null,
                    medKitName = null,
                    covered = coverage?.perSource?.firstOrNull { it.pkg == source.pkg }
                )
            },
            items = intakes.filterIsInstance<IntakeProjection.Scheduled>().map { it.toPresentationDTO(zone) },
            isRunning = isOpen,
            asksToCancel = cancelling.asking,
            isCancelling = cancelling.working,
            offPlanDoses = plan?.takenOffPlan?.count,
            asksOffPlan = counting.asking,
            isCounting = counting.working,
            message = cancelling.message ?: counting.message
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

    /** Чем кончилась поправка счёта: лечение этим и закончилось — или ничего не вышло. */
    private fun told(outcome: CourseOffPlanCounting.Outcome): Counting = when (outcome) {
        is CourseOffPlanCounting.Outcome.Set, CourseOffPlanCounting.Outcome.Gone -> Counting()
        // Лечение закончилось этим счётом: карточка сама покажет «Завершён», сказать нечего.
        CourseOffPlanCounting.Outcome.Finished -> Counting()
        CourseOffPlanCounting.Outcome.AlreadyFinished -> Counting(message = CourseCardMessage.AlreadyFinished)
        CourseOffPlanCounting.Outcome.Stale -> Counting(message = CourseCardMessage.Stale)
    }

    private data class Counting(
        val asking: Boolean = false,
        val working: Boolean = false,
        val message: CourseCardMessage? = null
    )

    private data class Cancelling(
        val asking: Boolean = false,
        val working: Boolean = false,
        val message: CourseCardMessage? = null
    )
}

/**
 * Что показывает карточка. [isGone] — эпизода нет вовсе; [isRunning] отличает идущее лечение от
 * законченного: у второго нет ни обеспечения, ни действий, а история остаётся.
 */
data class CourseCardUiState(
    val course: CoursePresentationDTO? = null,
    val coverage: CourseCoveragePresentationDTO? = null,
    val reductions: List<CoverageReductionPresentationDTO> = emptyList(),
    /** Чем лечение обеспечивают — коротко; весь стек человек правит на своём экране (H3 №16). */
    val sources: List<CourseSourcePresentationDTO> = emptyList(),
    val items: List<CourseItemPresentationDTO> = emptyList(),
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val asksToCancel: Boolean = false,
    val isCancelling: Boolean = false,
    /** Сколько доз принято мимо расписания: поправка к счёту, а не приёмы (PLAN D5). */
    val offPlanDoses: Int? = null,
    val asksOffPlan: Boolean = false,
    val isCounting: Boolean = false,
    /** Чем кончилось действие человека, если по самой карточке этого не видно. */
    val message: CourseCardMessage? = null
)

/**
 * Что карточка отвечает на «Отменить лечение», когда отменить не вышло. Отменённое лечение своего
 * случая здесь не имеет: о нём говорит сама карточка — плашкой «Отменён …» и пустым списком
 * источников (PLAN H3 №14).
 */
sealed interface CourseCardMessage {

    /** Лечение кончилось само, пока человек шёл сюда: отменять уже нечего. */
    data object AlreadyFinished : CourseCardMessage

    /** Лечение правили с другого экрана: карточка перечитает, а решение человек повторит. */
    data object Stale : CourseCardMessage
}
