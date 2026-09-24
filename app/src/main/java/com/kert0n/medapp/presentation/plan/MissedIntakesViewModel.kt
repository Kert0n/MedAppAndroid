package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.feature.course.CourseReadings
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeReadings
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.feature.notification.ReminderReadings
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.intake.toPresentationDTO
import com.kert0n.medapp.presentation.stateInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * **Попап пропущенного — последний шанс** (PLAN C1 «Попап пропущенного», решение владельца
 * 2026-09-16). В плане нельзя уйти в прошлые дни, поэтому неотвеченное вчера и раньше приходит при
 * входе: пришло уведомление, не пришло или его смахнули — неважно, важно, что ответа нет.
 *
 * Строки — пункты прошлых дней, ставшие пропуском **неответом** (их обязательство заводит отметка
 * пропуска, отказ человека его не заводит). «Принял» записывает плановое в момент пункта: человек
 * говорит, что выпил, как было назначено. Крестик отмечает сказанным всё, что в попапе, и
 * неотвеченное остаётся пропусками навсегда. Сегодняшнего здесь нет — оно на странице дня.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MissedIntakesViewModel @Inject constructor(
    private val outbox: ReminderOutbox,
    private val confirmation: IntakeConfirmation,
    today: Today,
    reminders: ReminderReadings,
    intakes: IntakeReadings,
    courses: CourseReadings,
    packages: PackageReadings,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    /** Закрытое крестиком — ключами, а не флагом: пропуск следующего дня приходит сам. */
    private val dismissed = MutableStateFlow<Set<NotificationKey>>(emptySet())

    /** По каким пунктам идёт запись: второе нажатие ничего не начинает. */
    private val answering = MutableStateFlow(emptySet<Uuid>())

    private val message = MutableStateFlow<DayMessage?>(null)

    /** Вопрос до записи — вместе с тем плановым, по которому отвечает «всё равно принял». */
    private val question = MutableStateFlow<Asked?>(null)

    private val notices = combine(reminders.observeAwaiting(NoticeDelivery.IN_APP_BANNER), dismissed) { all, closed ->
        all.filter { it.key.kind == NotificationKind.INTAKE_MISSED && it.key !in closed }
    }

    /**
     * Строки попапа — вместе с тем, по чему на них отвечают: ключом обязательства и плановым. Всё
     * едет **одним снимком** с нарисованным: нажатие читает то, что человек видел, а не переменную,
     * записанную сбоку, — та опережала бы экран на оборот потока или расходилась с ним фильтром
     * (C1 «Действие — по показанному»).
     */
    private val lines = combine(notices, today.observe()) { notices, day -> notices to day }
        .flatMapLatest { (notices, day) ->
            val keys = notices.mapNotNull { notice -> (notice.target as? NotificationTarget.Intake)?.let { it.intakeId to notice.key } }.toMap()
            combine(intakes.observeOfIds(keys.keys), courses.observeRecords()) { read, records -> read to records }
                .flatMapLatest { (read, records) ->
                    val titles = records.associate { it.id to it.title }
                    val waiting = read.filterIsInstance<IntakeProjection.Scheduled>()
                        .filter { it.status == IntakeStatus.MISSED && it.slot.localDate.isBefore(day.date) }
                    // Плановую коробку могли выбросить, пока попап ждал: «Принял» из неё — кнопка,
                    // которая ничего не сделает, кроме отказа (снимок BigLatest). Такой строке
                    // быстрого ответа нет; нажатие на неё ведёт на карточку пункта.
                    val planned = waiting.mapNotNullTo(LinkedHashSet()) { it.plannedPackage?.id }
                    val usable: Flow<Set<Uuid>> = if (planned.isEmpty()) flowOf(emptySet())
                    else combine(planned.map { id -> packages.observe(id) }) { boxes ->
                        boxes.filterNotNull().filter { it.status.allowsUse }.mapTo(HashSet()) { it.id }
                    }
                    usable.map { alive ->
                        waiting.mapNotNull { intake ->
                            val title = titles[intake.courseId] ?: return@mapNotNull null
                            val key = keys[intake.id] ?: return@mapNotNull null
                            val pkg = intake.plannedPackage?.id?.takeIf { it in alive }
                            // День — дата пункта, как на странице «Дня»: попап и «День» не расходятся.
                            // Время — по зоне телефона, как там же; расхождение зон — отдельный вопрос.
                            val row = intake.toDayRow(title, day.zone, on = intake.slot.localDate)
                            Line(
                                key = key,
                                row = if (pkg != null) row else row.copy(hasPlannedPackage = false),
                                answer = pkg?.let { MissedIntakesUiState.Planned(it, intake.plannedAmount, intake.slot.at) }
                            )
                        }
                    }
                }
        }

    val state: StateFlow<MissedIntakesUiState> = combine(lines, answering, message, question) { lines, answering, message, question ->
        MissedIntakesUiState(
            rows = lines.map { it.row.copy(isAnswering = it.row.intakeId in answering) },
            message = message,
            question = question?.question,
            told = lines.mapTo(HashSet()) { it.key },
            planned = lines.mapNotNull { line -> line.answer?.let { answer -> line.row.intakeId?.let { it to answer } } }.toMap()
        )
    }.stateInScreen(viewModelScope, reading, MissedIntakesUiState())

    /**
     * «Принял» за прошлый день: плановая пачка и доза, в момент пункта — тот же сценарий, что
     * быстрый ответ «Дня». Не вышло — сказано словами, а строка остаётся.
     *
     * Плановое приезжает **нажатием**, из того снимка, который человек видел: прочитай его здесь
     * заново — и записалась бы другая коробка, доза или минута, если поток успел провернуться
     * между взглядом и нажатием, а пропавшая строка съела бы ответ молча (C1 «Действие — по
     * показанному», CodeRabbit 4030390711).
     */
    fun confirm(intakeId: Uuid, planned: MissedIntakesUiState.Planned) = answer(intakeId, planned, acknowledged = false)

    /** «Всё равно принял»: то же плановое, что было нажато, — с подтверждением. */
    fun acknowledge() {
        val asked = question.value ?: return
        question.value = null
        answer(asked.question.intakeId, asked.planned, acknowledged = true)
    }

    /** Вопрос закрыт без ответа — не записано ничего (PLAN D6). */
    fun dismissQuestion() {
        question.value = null
    }

    private fun answer(intakeId: Uuid, planned: MissedIntakesUiState.Planned, acknowledged: Boolean) {
        if (intakeId in answering.value) return
        answering.update { it + intakeId }
        act(failures) {
            try {
                when (val outcome = confirmation.confirm(intakeId, planned.packageId, planned.amount, planned.at, acknowledged)) {
                    is IntakeConfirmation.Outcome.Confirmed -> Unit
                    is IntakeConfirmation.Outcome.Rejected -> message.value = DayMessage.Refused(outcome.reason)
                    IntakeConfirmation.Outcome.Gone -> message.value = DayMessage.Gone
                    is IntakeConfirmation.Outcome.Warned ->
                        question.value = Asked(DayQuestion(intakeId, outcome.warnings.map { it.toPresentationDTO() }), planned)
                }
            } finally {
                answering.update { it - intakeId }
            }
        }
    }

    /**
     * Крестик: сказанным отмечается **то, что в попапе стояло**, — неотвеченное остаётся пропусками
     * и больше не приходит. Скрытое (пункт сегодняшнего дня телефона) крестик не трогает: назавтра
     * у него свой последний шанс.
     *
     * Ключи приезжают нажатием, а не читаются заново: успей поток провернуться перед закрытием —
     * сказанной оказалась бы новость, которой человек не видел (CodeRabbit 4030390716).
     */
    fun dismiss(told: Set<NotificationKey>) {
        dismissed.update { it + told }
        act(failures, undo = { dismissed.update { it - told } }) { outbox.bannerShown(told) }
    }

    private data class Line(val key: NotificationKey, val row: DayItemPresentationDTO, val answer: MissedIntakesUiState.Planned?)

    private data class Asked(val question: DayQuestion, val planned: MissedIntakesUiState.Planned)

    fun dismissMessage() {
        message.value = null
    }
}

/**
 * Неотвеченные пункты прошлых дней — строками «Дня», со своим днём у каждой. [told] и [planned] не
 * рисуются: по ним отвечают крестик и «Принял», и едут они тем же снимком, что и строки.
 */
data class MissedIntakesUiState(
    val rows: List<DayItemPresentationDTO> = emptyList(),
    val message: DayMessage? = null,
    val question: DayQuestion? = null,
    /** Обязательства показанных строк: их и только их крестик отмечает сказанными. */
    val told: Set<NotificationKey> = emptySet(),
    /** Плановое строк, у которых есть быстрый ответ, — по номеру пункта. */
    val planned: Map<Uuid, Planned> = emptyMap()
) {
    /** Сказать нечего: ни строк, ни ответа, который человек ещё не прочёл, ни открытого вопроса. */
    val isEmpty: Boolean get() = rows.isEmpty() && message == null && question == null

    /** Что записывает «Принял»: плановая пачка и доза, в момент пункта. */
    data class Planned(val packageId: Uuid, val amount: Dose, val at: Instant)
}
