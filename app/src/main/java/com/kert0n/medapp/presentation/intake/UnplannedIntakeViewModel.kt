package com.kert0n.medapp.presentation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.pack.PackageStorageRepository
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
    packages: PackageStorageRepository,
    @Assisted private val packageId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): UnplannedIntakeViewModel
    }

    /** Что человек набрал; `null` — он ещё не трогал поле, и в нём стоит подсказка коробки. */
    private val typed = MutableStateFlow<UnplannedIntakePresentationDTO?>(null)

    private val recorded = MutableStateFlow(Recording())

    private val pack = packages.observe(packageId)

    val state: StateFlow<UnplannedIntakeUiState> = combine(pack, typed, recorded) { pack, typed, recorded ->
        if (pack == null) UnplannedIntakeUiState(isGone = true)
        else {
            val hint = pack.facts.defaultIntakeAmount?.quantity?.toPresentationDTO()
            UnplannedIntakeUiState(
                packageName = pack.facts.name,
                unit = pack.quantity.unit.toPresentationDTO(),
                availableToMe = pack.availability.availableToMe.toPresentationDTO(),
                // До первой правки в поле стоит подсказка коробки: человеку чаще всего её и нужно
                // подтвердить, а не набирать то же самое руками.
                form = typed ?: UnplannedIntakePresentationDTO(hint?.amount.orEmpty()),
                error = recorded.error,
                isBusy = recorded.busy,
                isRecorded = recorded.recorded
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnplannedIntakeUiState(isLoading = true))

    /** Правка поля: набранное человеком не затирается ничем, что дочитано из базы (U1). */
    fun edit(form: UnplannedIntakePresentationDTO) {
        typed.value = form
        if (recorded.value.error != null) recorded.value = recorded.value.copy(error = null)
    }

    /**
     * Записать приём. Признак работы ставится до обращения к сценарию, и второе нажатие ничего не
     * начинает: приём — факт о мире, и второго такого же человек не просил.
     */
    fun record() {
        val state = state.value
        val unit = state.unit ?: return
        if (recorded.value.busy || recorded.value.recorded) return
        recorded.value = Recording(busy = true)
        viewModelScope.launch {
            when (val parsed = state.form.parsed(unit, vocabulary.snapshot())) {
                is ParsedInput.Rejected -> recorded.value = Recording(error = parsed.error)
                is ParsedInput.Parsed -> recorded.value = told(recording.record(packageId, parsed.value, clock.instant()))
            }
        }
    }

    /**
     * Чем кончилась запись. Вопрос (`Warned`) сюда ещё не приходит: его задают на карточке пункта,
     * и лист отдаст его туда же, когда карточка появится (PLAN U4, коммит «Приём просроченного и
     * занятого спрашивает»).
     */
    private fun told(outcome: UnplannedIntakeRecording.Outcome): Recording = when (outcome) {
        is UnplannedIntakeRecording.Outcome.Recorded -> Recording(recorded = true)
        is UnplannedIntakeRecording.Outcome.Rejected -> Recording(error = UnplannedIntakeError.Rejected(outcome.reason))
        is UnplannedIntakeRecording.Outcome.Warned -> Recording()
    }

    private data class Recording(
        val busy: Boolean = false,
        val recorded: Boolean = false,
        val error: UnplannedIntakeError? = null
    )
}

/**
 * Что показывает лист разового приёма. [isRecorded] — приём записан, и лист закрывается: человек
 * сказал, что хотел. [isGone] — коробки больше нет, и принимать из неё нечего.
 */
data class UnplannedIntakeUiState(
    val packageName: String = "",
    val unit: UnitPresentationDTO? = null,
    /** Сколько в коробке моего — по нему человек и решает, сколько взять (PLAN D4). */
    val availableToMe: QuantityPresentationDTO? = null,
    val form: UnplannedIntakePresentationDTO = UnplannedIntakePresentationDTO(),
    val error: UnplannedIntakeError? = null,
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val isBusy: Boolean = false,
    val isRecorded: Boolean = false
)
