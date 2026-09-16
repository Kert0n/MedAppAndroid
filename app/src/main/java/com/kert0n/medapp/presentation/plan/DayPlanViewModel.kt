package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.report.DayPlan
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.platform.settings.DevicePermissions
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayPlanViewModel @Inject constructor(
    private val today: Today,
    private val planning: DayPlanning,
    private val confirmation: IntakeConfirmation,
    private val declining: IntakeDeclining,
    private val devicePermissions: DevicePermissions,
    private val readiness: NotificationReadiness,
    private val reminderAnswering: ReminderAnswering,
    private val clock: Clock,
    reminders: ReminderStorageRepository,
    intakes: IntakeStorageRepository,
    courses: CourseStorageRepository
) : ViewModel() {

    /**
     * О чём не смогли напомнить: обязательства, которые приложение не сказало, о пунктах **прошлых
     * дней** (PLAN C1 «Полка»). Сегодняшнее уже стоит в самом дне с кнопками — второй строкой его
     * человек читал бы как вторую дозу (разбор U5). Будущее сюда не попадает тем же правилом.
     *
     * Граница — день от [Today], а не `clock.instant()` при выдаче таблицы: полка меняется сменой дня,
     * даже когда обязательства не менялись (разбор #51). Сами приёмы читаются **по номерам** —
     * обязательство знает только его (PLAN D8), — а лечение им даёт запись эпизода.
     */
    private val unannounced: StateFlow<List<IntakeProjection.Scheduled>> = combine(
        reminders.observeAwaiting(NoticeDelivery.SYSTEM)
            .map { notices -> notices.mapNotNull { (it.target as? NotificationTarget.Intake)?.intakeId }.toSet() }
            .distinctUntilChanged(),
        today.observe()
    ) { ids, day -> ids to day.date }
        .distinctUntilChanged()
        .flatMapLatest { (ids, date) ->
            intakes.observeOfIds(ids).map { read ->
                read.filterIsInstance<IntakeProjection.Scheduled>().filter { it.slot.localDate.isBefore(date) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val titles = courses.observeRecords().map { records -> records.associate { it.id to it.title } }

    private val readings = mutableMapOf<Int, StateFlow<Reading?>>()

    private val pages = mutableMapOf<Int, StateFlow<ScreenState<DayPagePresentationDTO>>>()

    /** По каким пунктам прямо сейчас идёт запись: второе нажатие по ним ничего не начинает. */
    private val answering = MutableStateFlow(emptySet<Uuid>())

    /** Чем кончился ответ, если по самой странице этого не видно. */
    private val message = MutableStateFlow<DayMessage?>(null)

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
        combine(reading(daysAhead), answering, message, unannounced, titles) { reading, answering, message, notices, titles ->
            if (reading == null) ScreenState.Loading
            else ScreenState.Ready(
                reading.plan.toPresentationDTO(daysAhead, reading.zone, answering, message).copy(
                    // Полка стоит на сегодняшней странице: это разговор о **сейчас**, а не о том,
                    // что будет послезавтра.
                    unannounced = if (daysAhead != 0) emptyList()
                    else notices.mapNotNull { intake ->
                        val title = titles[intake.courseId] ?: return@mapNotNull null
                        intake.toDayRow(title, reading.zone, on = intake.slot.localDate).copy(
                            isAnswering = intake.id in answering,
                            // Пропуск неответом на полке признают: «Понятно» снимает строку, пункт
                            // остаётся пропуском (PLAN C1 «Полка»).
                            canAcknowledge = intake.status == IntakeStatus.MISSED
                        )
                    }
                )
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState.Loading)
    }

    /**
     * Быстрый ответ: принято то, что **уже записано в пункте**, — плановая пачка, плановая доза — и
     * в момент «сейчас». Человек нажал одну кнопку и ничего не называл, поэтому и берётся
     * назначенное, а не собранное экраном (H3 №12).
     */
    fun confirm(intakeId: Uuid) {
        if (intakeId in answering.value) return
        // Пункт без плановой пачки быстрым путём не отвечают: брать неоткуда, и человек выбирает
        // коробку на карточке. Кнопки у такой строки нет вовсе — нажимать нечего.
        val planned = plannedOf(intakeId) ?: return
        answering.value = answering.value + intakeId
        viewModelScope.launch {
            try {
                told(planned, confirmation.confirm(intakeId, planned.packageId, planned.amount, clock.instant()))
            } finally {
                // Сорвался сценарий или нет, строка должна снова принимать нажатие: иначе она
                // останется погашенной до конца жизни экрана.
                answering.value = answering.value - intakeId
            }
        }
    }

    /**
     * «Понятно» у пропуска на полке: человек прочёл, что доза пропущена, и согласен. Снимается
     * обещание сказать о пропуске, а сам пункт остаётся пропуском — отвечать за него «Принял»
     * по-прежнему можно из истории лечения (PLAN C1 «Полка»). Без этого честная ошибка висела бы
     * над каждым следующим днём до конца срока хранения.
     */
    fun acknowledge(intakeId: Uuid) {
        if (intakeId in answering.value) return
        answering.value = answering.value + intakeId
        viewModelScope.launch {
            try {
                reminderAnswering.acknowledge(intakeId)
            } finally {
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
        viewModelScope.launch {
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
        }
    }

    /** Чем кончился отказ. Записанный отказ приходит чтением; остальное — словами. */
    private fun told(outcome: IntakeDeclining.Outcome) {
        message.value = when (outcome) {
            IntakeDeclining.Outcome.DECLINED -> return
            IntakeDeclining.Outcome.ALREADY_ANSWERED -> DayMessage.AlreadyAnswered
            IntakeDeclining.Outcome.EPISODE_CLOSED -> DayMessage.EpisodeClosed
            IntakeDeclining.Outcome.GONE -> DayMessage.Gone
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
        // И среди того, о чём не смогли напомнить: эти пункты старше сегодняшней страницы, а
        // отвечают на них там же.
        val intake = (onPages + unannounced.value.asSequence()).firstOrNull { it.id == intakeId } ?: return null
        val pkg = intake.plannedPackage ?: return null
        return Planned(intake.courseId, intake.id, pkg.id, intake.plannedAmount)
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
        val intakeId: Uuid,
        val packageId: Uuid,
        val amount: Dose
    )
}

