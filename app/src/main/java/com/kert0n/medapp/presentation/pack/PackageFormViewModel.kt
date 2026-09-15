package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Заведение и правка упаковки (PLAN H3 №7, №8) — один экран в двух режимах: коробка названа или
 * нет.
 *
 * **В правке количество и аптечка не правятся.** У пересчёта и переноса свой след, а у правки
 * описания его нет и быть не должно: поправь количество здесь — и учёт разойдётся с тем, что
 * человек видел в коробке.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PackageFormViewModel.Factory::class)
class PackageFormViewModel @AssistedInject constructor(
    private val adding: PackageAdding,
    private val describing: PackageDescribing,
    private val packages: PackageStorageRepository,
    private val vocabulary: VocabularyStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    @Assisted private val opened: Opened
) : ViewModel() {

    /** Откуда экран открыт: с полки (тогда она подставлена) или у названной коробки. */
    data class Opened(val medKitId: Uuid? = null, val packageId: Uuid? = null)

    @AssistedFactory
    interface Factory {
        fun create(opened: Opened): PackageFormViewModel
    }

    private val form = MutableStateFlow(PackageFormPresentationDTO(medKitId = opened.medKitId))

    private val progress = MutableStateFlow(Progress())

    private val stored = MutableStateFlow<PackagePresentationDTO?>(null)

    /** Из чего человек выбирает: полки и словарь. Читается вместе — меняется редко. */
    private val choices = combine(
        today.observe().flatMapLatest { day -> medKits.observeAll(day.date) },
        vocabulary.observeUnits(),
        vocabulary.observeForms()
    ) { kits, units, forms ->
        Choices(
            medKits = kits.map { it.toPresentationDTO() },
            units = units.map { it.toPresentationDTO() },
            forms = forms.map { it.toPresentationDTO() }
        )
    }

    val state: StateFlow<PackageFormUiState> = combine(form, progress, stored, choices) { form, progress, stored, choices ->
        PackageFormUiState(
            form = form,
            isEditing = opened.packageId != null,
            stored = stored,
            medKits = choices.medKits,
            units = choices.units,
            forms = choices.forms,
            error = progress.error,
            isSaving = progress.isSaving,
            saved = progress.saved
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PackageFormUiState(form = form.value, isEditing = opened.packageId != null)
    )

    init {
        opened.packageId?.let { packageId -> viewModelScope.launch { open(packageId) } }
    }

    fun edit(edited: PackageFormPresentationDTO) {
        form.value = edited
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        if (progress.value.error != null) progress.value = progress.value.copy(error = null)
    }

    /**
     * Записать. Второе нажатие, пока идёт первое или пока уже записано, ничего не начинает:
     * человек ждёт от него той же коробки, а не второй. Признак работы ставится **до**
     * обращения к сценарию: между нажатием и записью стоит чтение словаря.
     */
    fun save() {
        val now = progress.value
        if (now.isSaving || now.saved != null) return
        progress.value = Progress(isSaving = true)
        viewModelScope.launch { write() }
    }

    private suspend fun open(packageId: Uuid) {
        val found = packages.observe(packageId).first()
        if (found == null) {
            progress.value = Progress(error = PackageFormError.PackageGone)
            return
        }
        stored.value = found.toPresentationDTO()
        form.value = found.toFormPresentationDTO()
    }

    private suspend fun write() {
        val described = when (val parsed = form.value.parsed(vocabulary.snapshot())) {
            is ParsedInput.Rejected -> return reject(parsed.error)
            is ParsedInput.Parsed -> parsed.value
        }
        val packageId = opened.packageId
        progress.value = if (packageId == null) {
            when (val outcome = adding.add(described.medKitId, described.facts, described.quantity)) {
                is PackageAdding.Outcome.Added -> Progress(saved = outcome.packageId)
                PackageAdding.Outcome.MedKitGone -> Progress(error = PackageFormError.MedKitGone)
                PackageAdding.Outcome.MedKitBusy -> Progress(error = PackageFormError.MedKitBusy)
            }
        } else {
            when (describing.describe(packageId, described.facts)) {
                PackageDescribing.Outcome.SAVED -> Progress(saved = packageId)
                PackageDescribing.Outcome.GONE -> Progress(error = PackageFormError.PackageGone)
                PackageDescribing.Outcome.UNUSABLE -> Progress(error = PackageFormError.PackageBusy)
                PackageDescribing.Outcome.FORM_CLEAR_UNSUPPORTED ->
                    Progress(error = PackageFormError.FormClearUnsupported)
            }
        }
    }

    private fun reject(error: PackageFormError) {
        progress.value = Progress(error = error)
    }

    private class Choices(
        val medKits: List<MedKitPresentationDTO>,
        val units: List<UnitPresentationDTO>,
        val forms: List<FormPresentationDTO>
    )

    private data class Progress(
        val isSaving: Boolean = false,
        val error: PackageFormError? = null,
        /** Записанная коробка: её и открывают следом — человек заводил её, чтобы посмотреть. */
        val saved: Uuid? = null
    )
}

/** Что показывает форма упаковки. */
data class PackageFormUiState(
    val form: PackageFormPresentationDTO,
    val isEditing: Boolean = false,
    /** Записанное: в правке количество и аптечку показывают отсюда, а не из полей ввода. */
    val stored: PackagePresentationDTO? = null,
    val medKits: List<MedKitPresentationDTO> = emptyList(),
    val units: List<UnitPresentationDTO> = emptyList(),
    val forms: List<FormPresentationDTO> = emptyList(),
    val error: PackageFormError? = null,
    val isSaving: Boolean = false,
    val saved: Uuid? = null
)
