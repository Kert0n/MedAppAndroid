package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.template.TemplateSearching
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.domain.template.TemplateQuery
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
 *
 * **Подсказки справочника — только при заведении** (U2). Запрос уходит, когда человек
 * остановился на [SUGGESTION_PAUSE], а не на каждую букву; новая пауза отменяет прежний запрос
 * вместе с его ответом, поэтому старый ответ не перекрывает новый — оба правила держит одно
 * `flatMapLatest`. Ответ справочника в форму не пишет ничего: пишет только выбор строки.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel(assistedFactory = PackageFormViewModel.Factory::class)
class PackageFormViewModel @AssistedInject constructor(
    private val adding: PackageAdding,
    private val describing: PackageDescribing,
    private val searching: TemplateSearching,
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

    /** Что человек печатает в названии. Выбор карточки сюда не пишет — иначе её имя тут же искалось бы заново. */
    private val typed = MutableStateFlow("")

    private val suggestions: Flow<Suggestions> =
        if (opened.packageId != null) flowOf<Suggestions>(Suggestions.None) else typed
            .debounce(SUGGESTION_PAUSE)
            .map { it.trim().take(TemplateQuery.MAX_LENGTH) }
            .distinctUntilChanged()
            .flatMapLatest { text ->
                if (text.isEmpty()) flowOf<Suggestions>(Suggestions.None)
                else flow<Suggestions> {
                    emit(Suggestions.Searching)
                    emit(
                        when (val search = searching.search(TemplateQuery(text))) {
                            is PackageTemplates.Search.Found -> Suggestions.Found(search.templates.map { it.toPresentationDTO() })
                            is PackageTemplates.Search.Unavailable -> Suggestions.Unavailable(search.reason)
                        }
                    )
                }
            }
            // До первой паузы подсказок нет — и состояние формы не ждёт паузы, чтобы появиться.
            .onStart { emit(Suggestions.None) }

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

    val state: StateFlow<PackageFormUiState> = combine(form, progress, stored, choices, suggestions) { form, progress, stored, choices, suggestions ->
        PackageFormUiState(
            form = form,
            isEditing = opened.packageId != null,
            stored = stored,
            medKits = choices.medKits,
            units = choices.units,
            forms = choices.forms,
            error = progress.error,
            isSaving = progress.isSaving,
            saved = progress.saved,
            suggestions = suggestions
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
        if (edited.name != form.value.name) typed.value = edited.name
        form.value = edited
        // Ввод снимает отказ: человек уже правит то, на что ему указали.
        if (progress.value.error != null) progress.value = progress.value.copy(error = null)
    }

    /**
     * Выбор подсказки заполняет только то, что знает карточка (PLAN H3 №7): название — её,
     * человек выбирал именно его; остальное — в пустые поля, введённое руками не затирается;
     * количество и срок не трогаются — их в справочнике нет. Печать после выбора ищет заново, а
     * карточка остаётся «откуда пришло».
     */
    fun pick(template: TemplatePresentationDTO) {
        val now = form.value
        form.value = now.copy(
            name = template.name,
            form = now.form ?: template.form,
            unit = now.unit ?: template.unit,
            category = now.category.ifBlank { template.category.orEmpty() },
            manufacturer = now.manufacturer.ifBlank { template.manufacturer.orEmpty() },
            country = now.country.ifBlank { template.country.orEmpty() },
            description = now.description.ifBlank { template.description.orEmpty() },
            templateId = template.id
        )
        // Выбранное — не напечатанное: искать имя карточки заново незачем, и список сворачивается.
        typed.value = ""
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
            when (val outcome = adding.add(described.medKitId, described.facts, described.quantity, described.templateId)) {
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

    private companion object {
        /** Пауза печати, после которой человек считается остановившимся (PLAN H3 №7, U2). */
        val SUGGESTION_PAUSE: Duration = 300.milliseconds
    }

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
    val saved: Uuid? = null,
    val suggestions: Suggestions = Suggestions.None
)

/**
 * Что справочник ответил на напечатанное (PLAN H3 №7). Случаи различает экран: ничего не
 * спрашивали; ждём; нашлось (пусто — «не нашлось», не ошибка); справочник недоступен — причина
 * словами, форма живёт.
 */
sealed interface Suggestions {
    data object None : Suggestions
    data object Searching : Suggestions
    data class Found(val templates: List<TemplatePresentationDTO>) : Suggestions
    data class Unavailable(val reason: Unavailability) : Suggestions
}
