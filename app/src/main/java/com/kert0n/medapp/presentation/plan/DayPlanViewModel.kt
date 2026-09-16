package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Страницы дня (PLAN H3 №12): что у человека назначено, что уже принято — и быстрый ответ прямо в
 * строке.
 *
 * Страница спрашивается **сдвигом** — сегодня, завтра, послезавтра, — и своего дня не заводит:
 * какой сегодня день и когда он сменится, знает `Today`, а что в этот день назначено —
 * `DayPlanning` (C1 «День — общая инфраструктура»). Оттого в полночь та же страница начинает
 * показывать новый день сама, и листать её для этого не надо.
 *
 * Зона приходит вместе с днём: время строки — время в зоне человека, и спрашивать её у экрана
 * значило бы завести второе мнение о том, где он живёт.
 *
 * У каждого сдвига своё чтение, и живёт оно, пока на страницу смотрят: листающий человек держит на
 * виду две страницы разом, и общее состояние показывало бы соседней чужие строки. Чтения хранятся
 * по сдвигу, потому что вернувшийся на вчерашнюю страницу ждёт её же, а не новой подписки.
 */
@HiltViewModel
class DayPlanViewModel @Inject constructor(
    private val today: Today,
    private val planning: DayPlanning,
    private val confirmation: IntakeConfirmation,
    private val clock: Clock
) : ViewModel() {

    private val readings = mutableMapOf<Int, StateFlow<Reading?>>()

    private val pages = mutableMapOf<Int, StateFlow<ScreenState<DayPagePresentationDTO>>>()

    /** По каким пунктам прямо сейчас идёт запись: второе нажатие по ним ничего не начинает. */
    private val answering = MutableStateFlow(emptySet<Uuid>())

    /** Пункт, о котором сценарий спросил: отвечать на вопрос человек идёт на карточку (H3 №12). */
    val asksAbout = MutableStateFlow<DayQuestion?>(null)

    /**
     * Страница дня, отстоящего от сегодняшнего на [daysAhead] дней. Спрашивается из вёрстки, то
     * есть с главного потока, — оттого и обычная карта без замка.
     *
     * Отказа у страницы нет: чтения местные. Пока первое значение не пришло — [ScreenState.Loading],
     * а «на этот день ничего не назначено» — это пришедшая пустая страница, а не ожидание.
     */
    fun page(daysAhead: Int): StateFlow<ScreenState<DayPagePresentationDTO>> = pages.getOrPut(daysAhead) {
        combine(reading(daysAhead), answering) { reading, answering ->
            if (reading == null) ScreenState.Loading
            else ScreenState.Ready(reading.plan.toPresentationDTO(daysAhead, reading.zone, answering))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState.Loading)
    }

    /**
     * Быстрый ответ: принято то, что **уже записано в пункте**, — плановая пачка, плановая доза — и
     * в момент «сейчас». Человек нажал одну кнопку и ничего не называл, поэтому и берётся
     * назначенное, а не собранное экраном (H3 №12).
     *
     * Вопрос сценария быстрый путь не проглатывает: на него человек отвечает зная, и путь ведёт к
     * карточке пункта, где вопросы показаны (D6 «вопрос — не отказ и не успех»).
     */
    fun confirm(intakeId: Uuid) {
        if (intakeId in answering.value) return
        val planned = plannedOf(intakeId) ?: return
        answering.value = answering.value + intakeId
        viewModelScope.launch {
            val outcome = confirmation.confirm(
                intakeId = intakeId,
                packageId = planned.packageId,
                amount = planned.amount,
                at = clock.instant()
            )
            answering.value = answering.value - intakeId
            if (outcome is IntakeConfirmation.Outcome.Warned) {
                asksAbout.value = DayQuestion(planned.courseId, intakeId)
            }
        }
    }

    /** Вопрос показан — карточка открыта, и второй раз открывать её незачем. */
    fun questionShown() {
        asksAbout.value = null
    }

    /**
     * Что записано в пункте: пачка, доза и лечение. Ищется среди страниц, на которые сейчас
     * смотрят, — номер приёма один на всё приложение, и гадать, с какой он страницы, незачем.
     */
    private fun plannedOf(intakeId: Uuid): Planned? = readings.values
        .asSequence()
        .mapNotNull { it.value }
        .flatMap { it.plan.items.asSequence() }
        .filterIsInstance<DayPlan.Item.Scheduled>()
        .firstOrNull { it.intake.id == intakeId }
        ?.let { item ->
            val pkg = item.intake.plannedPackage ?: return null
            Planned(item.intake.courseId, pkg.id, item.intake.plannedAmount)
        }

    private fun reading(daysAhead: Int): StateFlow<Reading?> = readings.getOrPut(daysAhead) {
        combine(today.observe(), planning.observe(daysAhead)) { day, plan -> Reading(plan, day.zone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** План дня и зона, в которой его читают: вместе, потому что порознь они врозь и устаревают. */
    private data class Reading(val plan: DayPlan, val zone: ZoneId)

    /** Плановое пункта — то, что берёт быстрый ответ. */
    private data class Planned(
        val courseId: Uuid,
        val packageId: Uuid,
        val amount: Dose
    )
}

/** Пункт, о котором сценарий спросил: карточка открывается по лечению и самому пункту. */
data class DayQuestion(val courseId: Uuid, val intakeId: Uuid)
