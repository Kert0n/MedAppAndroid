package com.kert0n.medapp.presentation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Карточка пункта плана (PLAN H3 №18): тот же ответ, что и в строке дня, но набранный руками —
 * другой дозой, другим моментом.
 *
 * Пункт читается **среди пунктов своего лечения**: своей строкой у чтения он не спрашивается, а
 * лечение карточке нужно всё равно — его именем она названа. Оттого и маршрут называет обоих.
 *
 * Записывает [IntakeConfirmation] — тот же сценарий, что и быстрый ответ: одно человеческое
 * действие живёт в одном месте, сколькими бы дорогами к нему ни приходили (PLAN F5).
 */
@HiltViewModel(assistedFactory = IntakeCardViewModel.Factory::class)
class IntakeCardViewModel @AssistedInject constructor(
    private val confirmation: IntakeConfirmation,
    private val declining: IntakeDeclining,
    private val vocabulary: VocabularyStorageRepository,
    private val today: Today,
    private val clock: Clock,
    courses: CourseStorageRepository,
    intakes: IntakeStorageRepository,
    @Assisted("courseId") private val courseId: Uuid,
    @Assisted("intakeId") private val intakeId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("courseId") courseId: Uuid,
            @Assisted("intakeId") intakeId: Uuid
        ): IntakeCardViewModel
    }

    /** Что человек набрал; `null` — он ещё не трогал карточку, и в ней стоит плановое. */
    private val typed = MutableStateFlow<IntakeCardPresentationDTO?>(null)

    private val writing = MutableStateFlow(Writing())

    private val episode = combine(courses.observeRecord(courseId), courses.observePlan(courseId)) { record, plan ->
        record to plan
    }

    val state: StateFlow<IntakeCardUiState> = combine(
        episode,
        intakes.observeOfCourse(courseId),
        today.observe(),
        typed,
        writing
    ) { (record, plan), intakes, day, typed, writing ->
        val intake = intakes.filterIsInstance<IntakeProjection.Scheduled>().firstOrNull { it.id == intakeId }
        // Пункта нет — расписание перестроили, пока карточку держали открытой: показывать нечего.
        if (record == null || intake == null) IntakeCardUiState(isGone = true)
        else intake.card(record.title, plan?.sources.orEmpty(), day.zone, typed, writing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntakeCardUiState(isLoading = true))

    fun edit(form: IntakeCardPresentationDTO) {
        typed.value = form
    }

    fun dismissQuestions() {
        writing.value = writing.value.copy(questions = emptyList())
    }

    /**
     * Записать приём. Второе нажатие, пока идёт первое, ничего не начинает: сторожем служит само
     * состояние, а не расторопность пальца.
     *
     * [acknowledged] — ответ на вопросы сценария: тот же вызов, повторённый с подтверждением
     * (PLAN D6). Своего решения карточка не принимает — вопрос задаёт сценарий, отвечает человек.
     */
    fun confirm(acknowledged: Boolean = false) {
        // Сторож — у самой записи, а не у её отражения: состояние собрано `stateIn` и отстаёт от
        // записи на оборот, и второе нажатие успевало бы начать второй приём.
        if (writing.value.busy) return
        val shown = state.value
        if (!shown.canAnswer) return
        val unit = shown.unit ?: return
        // Набранное берётся у самого набранного: между вводом и нажатием стоит поток, и палец
        // человека его не ждёт. Пачка — выбранная, сверенная с источниками лечения.
        val form = typed.value ?: shown.form
        val pkg = form.packageId?.takeIf { id -> shown.sources.any { it.id == id } }
            ?: shown.packageId
            ?: return
        writing.value = Writing(busy = true)
        viewModelScope.launch {
            val known = vocabulary.snapshot()
            when (val parsed = QuantityPresentationDTO(form.amount, unit).toDomain(known)) {
                is ParsedInput.Rejected -> writing.value = Writing(error = IntakeCardError.Amount(parsed.error))
                is ParsedInput.Parsed -> {
                    val at = moment(form, today.observe().first().zone)
                    writing.value = told(confirmation.confirm(intakeId, pkg, Dose(parsed.value), at, acknowledged))
                }
            }
        }
    }

    /**
     * Момент приёма — тот, что стоит в полях: человек либо оставил сегодняшний, либо назвал свой.
     */
    private fun moment(form: IntakeCardPresentationDTO, zone: ZoneId) =
        ZonedDateTime.of(
            requireNotNull(form.on) { "день приёма стоит в карточке заполненным" },
            requireNotNull(form.at) { "время приёма стоит в карточке заполненным" },
            zone
        ).toInstant()

    /**
     * Отказаться от приёма. Момент отказа — тот же, что у приёма: человек называет, когда это
     * было, а не «когда нажал» (PLAN D6). Ничего не списывается: расхода не было.
     *
     * Отказываются от того, на что ещё не отвечали: пропущенное уже пропущено, и сценарий ответил
     * бы «уже отвечено».
     */
    fun decline() {
        if (writing.value.busy) return
        val shown = state.value
        if (!shown.canDecline) return
        val form = typed.value ?: shown.form
        writing.value = Writing(busy = true)
        viewModelScope.launch {
            val at = moment(form, today.observe().first().zone)
            writing.value = told(declining.decline(intakeId, at))
        }
    }

    /** Чем кончился отказ: записанный уходит с карточки, остальное сказано словами по месту. */
    private fun told(outcome: IntakeDeclining.Outcome): Writing = when (outcome) {
        IntakeDeclining.Outcome.DECLINED -> Writing(done = true)
        // Пункта больше нет: показывать нечего, и чтение скажет то же самое.
        IntakeDeclining.Outcome.GONE -> Writing(done = true)
        IntakeDeclining.Outcome.ALREADY_ANSWERED -> Writing(error = IntakeCardError.AlreadyAnswered)
        IntakeDeclining.Outcome.EPISODE_CLOSED ->
            Writing(error = IntakeCardError.Rejected(IntakeRejected.Reason.EPISODE_CLOSED))
    }

    private fun told(outcome: IntakeConfirmation.Outcome): Writing = when (outcome) {
        // Записано — карточка уходит: человек отвечал на приём, а не заполнял форму.
        is IntakeConfirmation.Outcome.Confirmed -> Writing(done = true)
        // Пункта больше нет: показывать нечего, и чтение скажет то же самое.
        IntakeConfirmation.Outcome.Gone -> Writing(done = true)
        is IntakeConfirmation.Outcome.Warned -> Writing(questions = outcome.warnings)
        is IntakeConfirmation.Outcome.Rejected -> Writing(error = IntakeCardError.Rejected(outcome.reason))
    }

    /** Что идёт прямо сейчас: запись, вопрос к человеку или отказ, который он ещё не прочёл. */
    private data class Writing(
        val busy: Boolean = false,
        val questions: List<IntakeWarning> = emptyList(),
        val error: IntakeCardError? = null,
        val done: Boolean = false
    )

    /**
     * Пункт и то, что человек успел набрать, — в состояние карточки. Плановое стоит в полях, пока
     * его не тронули: чаще всего принимают именно назначенное, и заставлять человека набирать это
     * заново незачем (H3 №18).
     */
    private fun IntakeProjection.Scheduled.card(
        title: String,
        sources: List<CourseSource>,
        zone: ZoneId,
        typed: IntakeCardPresentationDTO?,
        writing: Writing
    ): IntakeCardUiState {
        // «Сейчас» — по часам приложения, а не по системным: иначе проверка живёт в одном времени,
        // а карточка в другом, и записанный момент разойдётся с тем, что считает сценарий.
        val nowHere = clock.instant().atZone(zone)
        // Принять можно из любого источника лечения: «беру из этой пачки» решается в момент
        // записи, а не при постройке плана (PLAN D6). Отключённый источник — не источник.
        val usable = sources.filter { it.fault == null }.map { IntakeSourcePresentationDTO(it.pkg.id, it.pkg.name) }
        val chosen = typed?.packageId?.takeIf { id -> usable.any { it.id == id } } ?: plannedPackage?.id
        return IntakeCardUiState(
            title = title,
            plannedOn = slot.localDate,
            plannedAt = slot.at.atZone(zone).toLocalTime(),
            packageId = chosen,
            packageName = usable.firstOrNull { it.id == chosen }?.name ?: plannedPackage?.name,
            sources = usable,
            plannedAmount = plannedAmount.quantity.toPresentationDTO(),
            unit = plannedAmount.unit.toPresentationDTO(),
            form = typed ?: IntakeCardPresentationDTO(
                amount = plannedAmount.quantity.toPresentationDTO().amount,
                on = nowHere.toLocalDate(),
                at = nowHere.toLocalTime().withSecond(0).withNano(0),
                packageId = plannedPackage?.id
            ),
            answer = when (status) {
                IntakeStatus.PLANNED -> null
                IntakeStatus.MISSED -> IntakeCardUiState.Answer.MISSED
                IntakeStatus.TAKEN -> IntakeCardUiState.Answer.TAKEN
                IntakeStatus.CANCELLED -> IntakeCardUiState.Answer.CANCELLED
            },
            answeredAt = answer?.at?.atZone(zone)?.toLocalTime(),
            questions = writing.questions.map { it.toPresentationDTO() },
            error = writing.error,
            isWriting = writing.busy,
            isDone = writing.done
        )
    }
}
