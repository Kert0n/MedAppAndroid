package com.kert0n.medapp.presentation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.feature.intake.IntakeReadings
import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.Fresh
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.readAfter
import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 *
 * Коробки-источники лечения при открытии перечитываются — когда план прочитан и известно, какие
 * они, — и пока сервер не ответил, карточка ждёт: выбирают пачку по свежему числу (PLAN E4).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = IntakeCardViewModel.Factory::class)
class IntakeCardViewModel @AssistedInject constructor(
    private val confirmation: IntakeConfirmation,
    private val declining: IntakeDeclining,
    freshening: Freshening,
    private val vocabulary: VocabularyStorageRepository,
    private val today: Today,
    private val clock: Clock,
    courses: CourseStorageRepository,
    intakes: IntakeReadings,
    packages: PackageReadings,
    @Assisted private val intakeId: Uuid,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    @AssistedFactory
    interface Factory {
        fun create(intakeId: Uuid): IntakeCardViewModel
    }

    /** Что человек набрал; `null` — он ещё не трогал карточку, и в ней стоит плановое. */
    private val typed = MutableStateFlow<IntakeCardPresentationDTO?>(null)

    private val writing = MutableStateFlow(Writing())

    /**
     * Карточка знает только **номер приёма**: столько же знает уведомление, которое сюда ведёт
     * (`NotificationTarget.Intake`). Лечение находится по самому приёму, а не приходит маршрутом —
     * иначе у одного места было бы два входа с разными знаниями (PLAN G3, H3 «Уведомления на экране»).
     */
    private val episode = intakes.observeOfIds(setOf(intakeId))
        .map { it.filterIsInstance<IntakeProjection.Scheduled>().firstOrNull { intake -> intake.id == intakeId } }
        .flatMapLatest { intake ->
            if (intake == null) flowOf(null to null)
            else combine(courses.observeRecord(intake.courseId), courses.observePlan(intake.courseId)) { record, plan ->
                intake to (record?.title to plan?.sources.orEmpty())
            }
        }

    /** Пункт с источниками, прочитанный после перечитывания их коробок (PLAN E4). */
    private val fresh = viewModelScope.readAfter(reading, {
        val sources = episode.first().second?.second.orEmpty()
        freshening.packs(sources.mapTo(HashSet()) { it.pkg.id })
    }) { episode }

    /**
     * Пока ждём, лечение и время видны из базы — ждёт только выбор пачки и дозы; после — всё
     * прочитанное после ответа.
     */
    private val shown = combine(episode, fresh) { live, fresh ->
        when (fresh) {
            Fresh.Waiting -> live to false
            is Fresh.Read -> fresh.value to true
        }
    }

    /**
     * Сроки коробок-источников: срок человек видит у выбранной коробки до нажатия, а не только в
     * вопросе после него (ТЗ 4.1.1.5.5).
     */
    private val expiries = episode
        .map { (_, episode) -> episode?.second.orEmpty().map { it.pkg.id }.toSet() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { packages.observe(it) }) { boxes ->
                boxes.filterNotNull().mapNotNull { box -> box.facts.expiresOn?.let { box.id to it } }.toMap()
            }
        }

    val state: StateFlow<IntakeCardUiState> = combine(
        shown,
        today.observe(),
        typed,
        writing,
        expiries
    ) { (read, freshened), day, typed, writing, expiries ->
        val (intake, episode) = read
        val title = episode?.first
        // Пункта нет — расписание перестроили, пока карточку держали открытой: показывать нечего.
        if (intake == null || title == null) IntakeCardUiState(isGone = true)
        else intake.card(title, episode.second.orEmpty(), day.zone, typed, writing, expiries).copy(isLoading = !freshened)
    }.stateInScreen(viewModelScope, reading, IntakeCardUiState(isLoading = true))

    fun edit(form: IntakeCardPresentationDTO) {
        typed.value = form
    }

    /**
     * Записать приём. Второе нажатие, пока идёт первое, ничего не начинает: сторожем служит само
     * состояние, а не расторопность пальца.
     */
    fun confirm(acknowledged: Boolean = false) {
        // Сторож — у самой записи, а не у её отражения: состояние собрано `stateIn` и отстаёт от
        // записи на оборот, и второе нажатие успевало бы начать второй приём.
        if (writing.value.busy) return
        val shown = state.value
        if (!shown.canAnswer) return
        val unit = shown.unit ?: return
        // Набранное берётся у самого набранного: между вводом и нажатием стоит поток, и палец
        // человека его не ждёт. Пачка — выбранная, сверенная с показанным: плановая или один из
        // источников. Выбора, которого больше нет, плановой не подменяют — человек её не выбирал
        // (C1 «Действие — по показанному»).
        val form = typed.value ?: shown.form
        val pkg = form.packageId?.takeIf { id -> id == shown.packageId || shown.sources.any { it.id == id } } ?: return
        writing.value = Writing(busy = true)
        act(failures, undo = { writing.value = Writing() }) {
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
        act(failures, undo = { writing.value = Writing() }) {
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
        is IntakeConfirmation.Outcome.Rejected -> Writing(error = IntakeCardError.Rejected(outcome.reason))
        // Вопрос — не записано ничего; ответ человека — тот же вызов с подтверждением.
        is IntakeConfirmation.Outcome.Warned -> Writing(questions = outcome.warnings)
    }

    /** Вопрос закрыт без ответа — не записано ничего: отмена и есть отказ записывать (PLAN D6). */
    fun dismissQuestions() {
        writing.value = writing.value.copy(questions = emptyList())
    }

    /** Что идёт прямо сейчас: запись, отказ, который человек ещё не прочёл, или вопрос до записи. */
    private data class Writing(
        val busy: Boolean = false,
        val error: IntakeCardError? = null,
        val questions: List<IntakeWarning> = emptyList(),
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
        writing: Writing,
        expiries: Map<Uuid, ExpiryDate>
    ): IntakeCardUiState {
        // «Сейчас» — по часам приложения, а не по системным: иначе проверка живёт в одном времени,
        // а карточка в другом, и записанный момент разойдётся с тем, что считает сценарий.
        val nowHere = clock.instant().atZone(zone)
        // Принять можно из любого источника лечения: «беру из этой пачки» решается в момент
        // записи, а не при постройке плана (PLAN D6). Отключённый источник — не источник.
        val usable = sources.filter { it.fault == null }.map { IntakeSourcePresentationDTO(it.pkg.id, it.pkg.name) }
        // Выбранное человеком, которого больше нет среди источников, — пустой выбор, а не плановая
        // коробка: подменённое молча списало бы не оттуда, откуда он брал.
        val picked = typed?.packageId?.takeIf { it != plannedPackage?.id }
        val chosen = if (picked != null) picked.takeIf { id -> usable.any { it.id == id } } else plannedPackage?.id
        val form = typed ?: IntakeCardPresentationDTO(
            amount = plannedAmount.quantity.toPresentationDTO().amount,
            on = nowHere.toLocalDate(),
            at = nowHere.toLocalTime().withSecond(0).withNano(0),
            packageId = plannedPackage?.id
        )
        return IntakeCardUiState(
            title = title,
            plannedOn = slot.localDate,
            plannedAt = slot.at.atZone(zone).toLocalTime(),
            packageId = chosen,
            packageName = usable.firstOrNull { it.id == chosen }?.name ?: plannedPackage?.name,
            sources = usable,
            plannedAmount = plannedAmount.quantity.toPresentationDTO(),
            unit = plannedAmount.unit.toPresentationDTO(),
            // Просрочена ли — к дню, которым человек пишет приём, как судит и сценарий.
            expired = chosen?.let { expiries[it] }
                ?.takeIf { it.isExpiredOn(form.on ?: nowHere.toLocalDate()) }
                ?.toPresentationDTO(),
            form = form,
            answer = when (status) {
                IntakeStatus.PLANNED -> null
                IntakeStatus.MISSED -> IntakeCardUiState.Answer.MISSED
                IntakeStatus.TAKEN -> IntakeCardUiState.Answer.TAKEN
                IntakeStatus.CANCELLED -> IntakeCardUiState.Answer.CANCELLED
            },
            answeredAt = answer?.at?.atZone(zone)?.toLocalTime(),
            error = writing.error,
            questions = writing.questions.map { it.toPresentationDTO() },
            isWriting = writing.busy,
            isDone = writing.done
        )
    }
}
