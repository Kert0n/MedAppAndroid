package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Заведение упаковки (PLAN H3 №7).
 *
 * Аптечка, единица и форма выбираются из того, что есть: списки приходят из базы и словаря, а
 * напечатать несуществующее нельзя по устройству формы. Аптечка подставлена та, из которой
 * человек пришёл, — её называет маршрут, и читается она из `SavedStateHandle`, чтобы пережить
 * смерть процесса.
 *
 * **Ввод человека не затирается тем, что дочитано из базы.** Списки выбора живут своим потоком,
 * набранное — своим: пришедшая из базы новая аптечка меняет список и не трогает ни одного
 * набранного символа (наследство разбора #16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PackageFormViewModel @Inject constructor(
    private val adding: PackageAdding,
    private val vocabulary: VocabularyStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val medKitId: Uuid? =
        savedState.get<String>(RouteArguments.MED_KIT_ID)?.takeIf { it != "null" }?.let(Uuid::parse)

    private val form = MutableStateFlow(PackageFormPresentationDTO(medKitId = medKitId))

    private val progress = MutableStateFlow(Progress())

    val state: StateFlow<PackageFormUiState> = combine(
        form,
        progress,
        today.observe().flatMapLatest { medKits.observeAll(it) },
        vocabulary.observeUnits(),
        vocabulary.observeForms()
    ) { form, progress, medKits, units, forms ->
        PackageFormUiState(
            form = form,
            medKits = medKits.map { it.toPresentationDTO() },
            units = units.map { it.toPresentationDTO() },
            forms = forms.map { it.toPresentationDTO() },
            error = progress.error,
            isSaving = progress.isSaving,
            saved = progress.saved
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PackageFormUiState(form = form.value)
    )

    fun edit(edited: PackageFormPresentationDTO) {
        form.value = edited
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        if (progress.value.error != null) progress.value = progress.value.copy(error = null)
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, не делает ничего: человек
     * нажимает ещё раз, не дождавшись ответа, и ждёт от этого той же коробки, а не второй.
     * Сторожем служит само состояние записи, а не признак рядом с ним.
     */
    fun save() {
        val now = progress.value
        if (now.isSaving || now.saved != null) return
        progress.value = Progress(isSaving = true)
        viewModelScope.launch { write() }
    }

    private suspend fun write() {
        val typed = form.value
        val medKitId = typed.medKitId ?: return reject(PackageFormError.MedKitMissing)
        val known = vocabulary.snapshot()
        // Количество разбирается первым: без него коробки не бывает, и человеку важнее узнать
        // об этом, чем о слишком длинном описании.
        val quantity = when (val parsed = typed.parsedQuantity(known)) {
            is ParsedInput.Rejected -> return reject(parsed.error)
            is ParsedInput.Parsed -> parsed.value
        }
        val facts = when (val parsed = typed.parsedFacts(known)) {
            is ParsedInput.Rejected -> return reject(parsed.error)
            is ParsedInput.Parsed -> parsed.value
        }
        progress.value = when (val outcome = adding.add(medKitId, facts, quantity)) {
            is PackageAdding.Outcome.Added -> Progress(saved = outcome.packageId)
            PackageAdding.Outcome.MedKitGone -> Progress(error = PackageFormError.MedKitGone)
            PackageAdding.Outcome.MedKitBusy -> Progress(error = PackageFormError.MedKitBusy)
        }
    }

    private fun reject(error: PackageFormError) {
        progress.value = Progress(error = error)
    }

    /** Что с записью: идёт, отказана — или кончилась заведённой коробкой. */
    private data class Progress(
        val isSaving: Boolean = false,
        val error: PackageFormError? = null,
        val saved: Uuid? = null
    )
}

/**
 * Что показывает форма упаковки. Списки выбора и набранное человеком лежат рядом, но порознь:
 * пришедшая из базы аптечка меняет список и не трогает ввод.
 */
data class PackageFormUiState(
    val form: PackageFormPresentationDTO,
    val medKits: List<MedKitPresentationDTO> = emptyList(),
    val units: List<UnitPresentationDTO> = emptyList(),
    val forms: List<FormPresentationDTO> = emptyList(),
    val error: PackageFormError? = null,
    val isSaving: Boolean = false,
    /** Коробка заведена: экран уходит на её карточку. */
    val saved: Uuid? = null
)
