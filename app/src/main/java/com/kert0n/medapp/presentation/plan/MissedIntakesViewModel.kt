package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    reminders: ReminderStorageRepository,
    intakes: IntakeStorageRepository,
    courses: CourseStorageRepository,
    packages: PackageStorageRepository
) : ViewModel() {

    /** Закрытое крестиком — ключами, а не флагом: пропуск следующего дня приходит сам. */
    private val dismissed = MutableStateFlow<Set<NotificationKey>>(emptySet())

    /** По каким пунктам идёт запись: второе нажатие ничего не начинает. */
    private val answering = MutableStateFlow(emptySet<Uuid>())

    private val message = MutableStateFlow<DayMessage?>(null)

    private val notices = combine(reminders.observeAwaiting(NoticeDelivery.IN_APP_BANNER), dismissed) { all, closed ->
        all.filter { it.key.kind == NotificationKind.INTAKE_MISSED && it.key !in closed }
    }

    /** Что в попапе сейчас: закрывая, отмечается сказанным именно это. */
    private var listed: Set<NotificationKey> = emptySet()

    /** Пункты, по которым отвечают, — читаются заново перед каждой записью не экраном, а сценарием. */
    private var intakesShown: List<IntakeProjection.Scheduled> = emptyList()

    private val rows = combine(notices, today.observe()) { notices, day -> notices to day }
        .flatMapLatest { (notices, day) ->
            listed = notices.mapTo(HashSet()) { it.key }
            val ids = notices.mapNotNullTo(HashSet()) { (it.target as? NotificationTarget.Intake)?.intakeId }
            combine(intakes.observeOfIds(ids), courses.observeRecords()) { read, records -> read to records }
                .flatMapLatest { (read, records) ->
                    val titles = records.associate { it.id to it.title }
                    val waiting = read.filterIsInstance<IntakeProjection.Scheduled>()
                        .filter { it.status == IntakeStatus.MISSED && it.slot.localDate.isBefore(day.date) }
                    intakesShown = waiting
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
                            intake.toDayRow(title, day.zone, on = intake.slot.localDate).let { row ->
                                if (intake.plannedPackage?.id in alive) row else row.copy(hasPlannedPackage = false)
                            }
                        }
                    }
                }
        }

    val state: StateFlow<MissedIntakesUiState> = combine(rows, answering, message) { rows, answering, message ->
        MissedIntakesUiState(rows.map { it.copy(isAnswering = it.intakeId in answering) }, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MissedIntakesUiState())

    /**
     * «Принял» за прошлый день: плановая пачка и доза, в момент пункта — тот же сценарий, что
     * быстрый ответ «Дня». Не вышло — сказано словами, а строка остаётся.
     */
    fun confirm(intakeId: Uuid) {
        if (intakeId in answering.value) return
        val intake = intakesShown.firstOrNull { it.id == intakeId } ?: return
        val pkg = intake.plannedPackage ?: return
        answering.update { it + intakeId }
        viewModelScope.launch {
            try {
                when (val outcome = confirmation.confirm(intakeId, pkg.id, intake.plannedAmount, intake.slot.at)) {
                    is IntakeConfirmation.Outcome.Confirmed -> Unit
                    is IntakeConfirmation.Outcome.Rejected -> message.value = DayMessage.Refused(outcome.reason)
                    IntakeConfirmation.Outcome.Gone -> message.value = DayMessage.Gone
                }
            } finally {
                answering.update { it - intakeId }
            }
        }
    }

    /** Крестик: всё, что в попапе, сказано; неотвеченное остаётся пропусками и больше не приходит. */
    fun dismiss() {
        val keys = listed
        dismissed.update { it + keys }
        viewModelScope.launch { outbox.bannerShown(keys) }
    }

    fun dismissMessage() {
        message.value = null
    }
}

/** Неотвеченные пункты прошлых дней — строками «Дня», со своим днём у каждой. */
data class MissedIntakesUiState(
    val rows: List<DayItemPresentationDTO> = emptyList(),
    val message: DayMessage? = null
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
