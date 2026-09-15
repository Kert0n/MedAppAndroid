package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * Заведение (PLAN H3 №7) и правка (№8) упаковки. Экран один: поля те же и разбор тот же, а
 * различает их то, есть ли уже коробка.
 *
 * **В правке количество и аптечка показаны, но не правятся.** Количество двигают пересчёт и
 * утилизация, место — перенос; у обоих есть свой след, а у правки описания его нет и быть не
 * должно (PLAN D3, D7).
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
    private val describing: PackageDescribing,
    private val vocabulary: VocabularyStorageRepository,
    private val packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val medKitId: Uuid? =
        savedState.get<String>(RouteArguments.MED_KIT_ID)?.takeIf { it != "null" }?.let(Uuid::parse)

    private val packageId: Uuid? =
        savedState.get<String>(RouteArguments.PACKAGE_ID)?.takeIf { it != "null" }?.let(Uuid::parse)

    private val form = MutableStateFlow(PackageFormPresentationDTO(medKitId = medKitId))

    /** Что записано у коробки, которую правят: количество и аптечку экран показывает отсюда. */
    private val stored = MutableStateFlow<PackagePresentationDTO?>(null)

    private val progress = MutableStateFlow(Progress())

    init {
        // Записанное дочитывается **один раз**, при открытии: подписка перетирала бы набранное
        // каждым изменением в базе (наследство разбора #16).
        if (packageId != null) viewModelScope.launch { open(packageId) }
    }

    val state: StateFlow<PackageFormUiState> = combine(
        combine(form, stored) { form, stored -> form to stored },
        progress,
        today.observe().flatMapLatest { medKits.observeAll(it) },
        vocabulary.observeUnits(),
        vocabulary.observeForms()
    ) { (form, stored), progress, medKits, units, forms ->
        PackageFormUiState(
            form = form,
            stored = stored,
            isEditing = packageId != null,
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
        if (packageId != null) return rewrite(packageId)
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

    /** Правка: количество и место не трогаются — сценарий принимает одни сведения (PLAN D3). */
    private suspend fun rewrite(packageId: Uuid) {
        val facts = when (val parsed = form.value.parsedFacts(vocabulary.snapshot())) {
            is ParsedInput.Rejected -> return reject(parsed.error)
            is ParsedInput.Parsed -> parsed.value
        }
        progress.value = when (describing.describe(packageId, facts)) {
            PackageDescribing.Outcome.SAVED -> Progress(saved = packageId)
            PackageDescribing.Outcome.GONE -> Progress(error = PackageFormError.PackageGone)
            PackageDescribing.Outcome.UNUSABLE -> Progress(error = PackageFormError.PackageBusy)
            PackageDescribing.Outcome.FORM_CLEAR_UNSUPPORTED ->
                Progress(error = PackageFormError.FormClearUnsupported)
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
    /** Правят записанную коробку или заводят новую: у полей об этом не спрашивают. */
    val isEditing: Boolean = false,
    /** Что записано у правимой коробки: количество и аптечку экран показывает, но не правит. */
    val stored: PackagePresentationDTO? = null,
    val medKits: List<MedKitPresentationDTO> = emptyList(),
    val units: List<UnitPresentationDTO> = emptyList(),
    val forms: List<FormPresentationDTO> = emptyList(),
    val error: PackageFormError? = null,
    val isSaving: Boolean = false,
    /** Коробка заведена: экран уходит на её карточку. */
    val saved: Uuid? = null
)
