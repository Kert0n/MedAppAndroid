package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.settings.DevicePermissions
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.intake.toPresentationDTO
import com.kert0n.medapp.presentation.stateInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayPlanViewModel @Inject constructor(
    private val today: Today,
    private val planning: DayPlanning,
    private val confirmation: IntakeConfirmation,
    private val declining: IntakeDeclining,
    private val devicePermissions: DevicePermissions,
    private val readiness: NotificationReadiness,
    private val clock: Clock,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    private val readings = mutableMapOf<Int, StateFlow<Reading?>>()

    private val pages = mutableMapOf<Int, StateFlow<ScreenState<DayPagePresentationDTO>>>()

    /** По каким пунктам прямо сейчас идёт запись: второе нажатие по ним ничего не начинает. */
    private val answering = MutableStateFlow(emptySet<Uuid>())

    /** Чем кончился ответ, если по самой странице этого не видно. */
    private val message = MutableStateFlow<DayMessage?>(null)

    /** Вопрос до записи быстрого ответа: ответ на него — тот же вызов с подтверждением. */
    private val question = MutableStateFlow<DayQuestion?>(null)

    /**
     * Что мешает напомнить вовремя. Состояние спрашивается у системы, а меняет его человек в её
     * настройках — поэтому перечитывается при каждом возвращении на экран ([refreshPermissions]),
     * а не один раз при создании.
     */
    private val quiet = MutableStateFlow(permissionsNow())

    val permissions: StateFlow<DayPermissionsPresentationDTO> = quiet

    /**
     * Что мешает напомнить — словами экрана. Можно ли сказать, отвечает тот же [NotificationReadiness],
     * что и показ: заглушённый канал «Приёмы» иначе молчал бы, а день говорил, что всё хорошо (PLAN C1).
     * Беды называются по одной и по порядку: без разрешения о канале и точности говорить нечего.
     */
    private fun permissionsNow(): DayPermissionsPresentationDTO {
        val readiness = readiness.now()
        val intakesMuted = readiness.allowed && NotificationChannel.INTAKES in readiness.muted
        return DayPermissionsPresentationDTO(
            notificationsOff = !readiness.allowed,
            intakesMuted = intakesMuted,
            alarmsInexact = readiness.canSay(NotificationChannel.INTAKES) && !devicePermissions.current().exactAlarms
        )
    }

    /** Человек вернулся из системных настроек: спрашиваем заново — там он мог всё и починить. */
    fun refreshPermissions() {
        quiet.value = permissionsNow()
    }

    /**
     * Страница дня, отстоящего от сегодняшнего на [daysAhead] дней. Спрашивается из вёрстки, то
     * есть с главного потока, — оттого и обычная карта без замка.
     *
     * Отказа у страницы нет: чтения местные. Пока первое значение не пришло — [ScreenState.Loading],
     * а «на этот день ничего не назначено» — это пришедшая пустая страница, а не ожидание.
     */
    fun page(daysAhead: Int): StateFlow<ScreenState<DayPagePresentationDTO>> = pages.getOrPut(daysAhead) {
        combine(reading(daysAhead), answering, message, question) { reading, answering, message, question ->
            if (reading == null) ScreenState.Loading
            else ScreenState.Ready(reading.plan.toPresentationDTO(daysAhead, reading.zone, answering, message).copy(question = question))
        }.stateInScreen(viewModelScope, reading, ScreenState.Loading)
    }

    /**
     * Быстрый ответ: принято то, что **уже записано в пункте**, — плановая пачка, плановая доза — и
     * в момент «сейчас». Человек нажал одну кнопку и ничего не называл, поэтому и берётся
     * назначенное, а не собранное экраном (H3 №12).
     */
    fun confirm(intakeId: Uuid, acknowledged: Boolean = false) {
        if (intakeId in answering.value) return
        // Пункт без плановой пачки быстрым путём не отвечают: брать неоткуда, и человек выбирает
        // коробку на карточке. Кнопки у такой строки нет вовсе — нажимать нечего.
        val planned = plannedOf(intakeId) ?: return
        answering.value = answering.value + intakeId
        act(failures) {
            try {
                told(planned, confirmation.confirm(intakeId, planned.packageId, planned.amount, clock.instant(), acknowledged))
            } finally {
                // Сорвался сценарий или нет, строка должна снова принимать нажатие: иначе она
                // останется погашенной до конца жизни экрана.
                answering.value = answering.value - intakeId
            }
        }
    }

    /**
     * Отказ от приёма — **решение**, а не молчание: человек говорит «не принял», и лечение считает
     * эту дозу пропущенной (PLAN D6). Ничего не списывается: расхода не было.
     *
     * Вопросов у отказа нет — спрашивать не о чем, ничего не тратится. Сторож тот же, что у
     * подтверждения: второе нажатие, пока идёт первое, ничего не начинает.
     */
    fun decline(intakeId: Uuid) {
        if (intakeId in answering.value) return
        answering.value = answering.value + intakeId
        act(failures) {
            try {
                told(declining.decline(intakeId, clock.instant()))
            } finally {
                answering.value = answering.value - intakeId
            }
        }
    }

    /**
     * Чем кончился быстрый ответ. Записанное человек видит чтением — строка меняется сама; всё
     * остальное сказать надо: вопрос ведёт на карточку, где он виден, отказ — словами по месту, а
     * пропавший пункт уходит со страницы вместе с чтением.
     */
    private fun told(planned: Planned, outcome: IntakeConfirmation.Outcome) {
        when (outcome) {
            is IntakeConfirmation.Outcome.Confirmed -> Unit
            is IntakeConfirmation.Outcome.Rejected -> message.value = DayMessage.Refused(outcome.reason)
            IntakeConfirmation.Outcome.Gone -> message.value = DayMessage.Gone
            is IntakeConfirmation.Outcome.Warned ->
                question.value = DayQuestion(planned.intakeId, outcome.warnings.map { it.toPresentationDTO() })
        }
    }

    /** «Всё равно принял»: тот же быстрый ответ, но с подтверждением — и вопрос закрыт. */
    fun acknowledge() {
        val asked = question.value ?: return
        question.value = null
        confirm(asked.intakeId, acknowledged = true)
    }

    /** Вопрос закрыт без ответа — не записано ничего: отмена и есть отказ записывать (PLAN D6). */
    fun dismissQuestion() {
        question.value = null
    }

    /** Чем кончился отказ. Записанный отказ приходит чтением; остальное — словами. */
    private fun told(outcome: IntakeDeclining.Outcome) {
        message.value = when (outcome) {
            IntakeDeclining.Outcome.Declined -> return
            IntakeDeclining.Outcome.AlreadyAnswered -> DayMessage.AlreadyAnswered
            is IntakeDeclining.Outcome.Rejected -> DayMessage.EpisodeClosed
            IntakeDeclining.Outcome.Gone -> DayMessage.Gone
        }
    }

    /** Прочитанное сообщение человек уносит сам. */
    fun dismissMessage() {
        message.value = null
    }

    /**
     * Что записано в пункте: пачка, доза и лечение. Ищется среди страниц, на которые сейчас
     * смотрят, — номер приёма один на всё приложение, и гадать, с какой он страницы, незачем.
     */
    private fun plannedOf(intakeId: Uuid): Planned? {
        val onPages = readings.values.asSequence()
            .mapNotNull { it.value }
            .flatMap { it.plan.items.asSequence() }
            .filterIsInstance<DayPlan.Item.Scheduled>()
            .map { it.intake }
        val intake = onPages.firstOrNull { it.id == intakeId } ?: return null
        val pkg = intake.plannedPackage ?: return null
        return Planned(intake.courseId, intake.id, pkg.id, intake.plannedAmount)
    }

    private fun reading(daysAhead: Int): StateFlow<Reading?> = readings.getOrPut(daysAhead) {
        combine(today.observe(), planning.observe(daysAhead)) { day, plan -> Reading(plan, day.zone) }
            .stateInScreen(viewModelScope, reading, null)
    }

    /** План дня и зона, в которой его читают: вместе, потому что порознь они врозь и устаревают. */
    private data class Reading(val plan: DayPlan, val zone: ZoneId)

    /** Плановое пункта — то, что берёт быстрый ответ. */
    private data class Planned(
        val courseId: Uuid,
        val intakeId: Uuid,
        val packageId: Uuid,
        val amount: Dose
    )
}

