package com.kert0n.medapp.presentation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.presentation.Fresh
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.readAfter
import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Разовый приём из коробки (PLAN H3 №10, D6). Человек выпил таблетку просто так — вне всякого
 * лечения, — и говорит об этом с карточки коробки.
 *
 * **Доза подставлена, но не навязана** (ТЗ 4.1.1.4.2): подсказка коробки стоит в поле готовым
 * ответом, и человек её переписывает. Единица не спрашивается вовсе — её знает коробка, и другой
 * у приёма из неё быть не может.
 *
 * Момент приёма — «сейчас»: назвать другой человек сможет на карточке пункта, где для этого есть
 * место. Пока он не назван, спрашивать его пустым полем незачем.
 */
@HiltViewModel(assistedFactory = UnplannedIntakeViewModel.Factory::class)
class UnplannedIntakeViewModel @AssistedInject constructor(
    private val recording: UnplannedIntakeRecording,
    private val vocabulary: VocabularyStorageRepository,
    private val clock: Clock,
    freshening: Freshening,
    packages: PackageReadings,
    @Assisted private val packageId: Uuid,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): UnplannedIntakeViewModel
    }

    /** Что человек набрал; `null` — он ещё не трогал поле, и в нём стоит подсказка коробки. */
    private val typed = MutableStateFlow<UnplannedIntakePresentationDTO?>(null)

    private val recorded = MutableStateFlow(Recording())

    /** Коробка, прочитанная после перечитывания (PLAN E4): до него — ожидание. */
    private val fresh = viewModelScope.readAfter(reading, { freshening.pack(packageId) }) { packages.observe(packageId) }

    val state: StateFlow<UnplannedIntakeUiState> = combine(fresh, typed, recorded) { fresh, typed, recorded ->
        val pack = (fresh as? Fresh.Read)?.value
        // Пока коробка общей полки перечитывается, лист ждёт: «свободно N из M» должно быть свежим,
        // и «коробки нет» до ответа сервера не говорится (PLAN E4).
        if (fresh is Fresh.Waiting) UnplannedIntakeUiState(isLoading = true)
        else if (pack == null) UnplannedIntakeUiState(isGone = true)
        else {
            val hint = pack.facts.defaultIntakeAmount?.quantity?.toPresentationDTO()
            UnplannedIntakeUiState(
                packageName = pack.facts.name,
                unit = pack.quantity.unit.toPresentationDTO(),
                // То же число, по которому судит сценарий: он спрашивает, когда приём заденет
                // занятое, и спрашивает он по «свободно любому» (PLAN D4).
                free = pack.availability.freeForAnyone.toPresentationDTO(),
                inTheBox = pack.availability.effective.toPresentationDTO(),
                // Срок человек видит до нажатия, а не только в вопросе после него.
                expired = pack.facts.expiresOn
                    ?.takeIf { it.isExpiredOn(clock.instant().atZone(clock.zone).toLocalDate()) }
                    ?.toPresentationDTO(),
                // До первой правки в поле стоит подсказка коробки: человеку чаще всего её и нужно
                // подтвердить, а не набирать то же самое руками.
                form = typed ?: UnplannedIntakePresentationDTO(hint?.amount.orEmpty()),
                error = recorded.error,
                questions = recorded.questions.map { it.toPresentationDTO() },
                isBusy = recorded.busy,
                isRecorded = recorded.recorded
            )
        }
    }.stateInScreen(viewModelScope, reading, UnplannedIntakeUiState(isLoading = true))

    /** Правка поля: набранное человеком не затирается ничем, что дочитано из базы (U1). */
    fun edit(form: UnplannedIntakePresentationDTO) {
        typed.value = form
        if (recorded.value.error != null) recorded.value = recorded.value.copy(error = null)
    }

    /**
     * Записать приём. Признак работы ставится до обращения к сценарию, и второе нажатие ничего не
     * начинает: приём — факт о мире, и второго такого же человек не просил.
     */
    fun record(acknowledged: Boolean = false) {
        if (recorded.value.busy || recorded.value.recorded) return
        val state = state.value
        val unit = state.unit ?: return
        // Набранное берётся у самого набранного, а не у состояния: между вводом и нажатием стоит
        // поток, и торопливый палец записал бы подсказку коробки вместо своего числа.
        val form = typed.value ?: state.form
        recorded.value = Recording(busy = true)
        act(failures, undo = { recorded.value = Recording() }) {
            when (val parsed = form.parsed(unit, vocabulary.snapshot())) {
                is ParsedInput.Rejected -> recorded.value = Recording(error = parsed.error)
                is ParsedInput.Parsed ->
                    recorded.value = told(recording.record(packageId, parsed.value, clock.instant(), acknowledged))
            }
        }
    }

    /** Вопрос закрыт без ответа — не записано ничего: отмена и есть отказ записывать (PLAN D6). */
    fun dismissQuestions() {
        recorded.value = recorded.value.copy(questions = emptyList())
    }

    /**
     * Лист закрыт — разговор окончен. Следующее «Принять» начинает **новый** приём: без этого
     * записанный остаётся записанным, и открытый заново лист закрывается сам, ничего не спросив.
     */
    fun forgetTheIntake() {
        typed.value = null
        recorded.value = Recording()
    }

    /**
     * Чем кончилась запись. Вопрос — третий исход рядом с записью и отказом: не записано ничего,
     * пока человек не ответит, и ответ — тот же вызов с подтверждением (PLAN D6).
     */
    private fun told(outcome: UnplannedIntakeRecording.Outcome): Recording = when (outcome) {
        is UnplannedIntakeRecording.Outcome.Recorded -> Recording(recorded = true)
        is UnplannedIntakeRecording.Outcome.Rejected -> Recording(error = UnplannedIntakeError.Rejected(outcome.reason))
        is UnplannedIntakeRecording.Outcome.Warned -> Recording(questions = outcome.warnings)
    }

    private data class Recording(
        val busy: Boolean = false,
        val recorded: Boolean = false,
        val error: UnplannedIntakeError? = null,
        val questions: List<IntakeWarning> = emptyList()
    )
}

/**
 * Что показывает лист разового приёма. [isRecorded] — приём записан, и лист закрывается: человек
 * сказал, что хотел. [isGone] — коробки больше нет, и принимать из неё нечего.
 */
data class UnplannedIntakeUiState(
    val packageName: String = "",
    val unit: UnitPresentationDTO? = null,
    /**
     * Сколько в коробке **никем не занято** и сколько в ней всего. Взять можно и больше — коробка
     * стоит на полке, и физически её никто не держит, — но это заденет занятое, и сценарий об этом
     * спросит (PLAN D4).
     */
    val free: QuantityPresentationDTO? = null,
    val inTheBox: QuantityPresentationDTO? = null,
    /** Коробка просрочена к сегодняшнему дню — годна была до этого срока; иначе `null`. */
    val expired: ExpiryDatePresentationDTO? = null,
    val form: UnplannedIntakePresentationDTO = UnplannedIntakePresentationDTO(),
    val error: UnplannedIntakeError? = null,
    /** О чём сценарий спросил до записи: пока на это не ответили, не записано ничего (PLAN D6). */
    val questions: List<IntakeQuestionPresentationDTO> = emptyList(),
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val isBusy: Boolean = false,
    val isRecorded: Boolean = false
)
