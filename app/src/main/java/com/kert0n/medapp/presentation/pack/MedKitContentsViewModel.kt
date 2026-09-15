package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства сразу (№5). Экран один, областей две: аптечка
 * названа или не названа, и это единственное, чем они различаются.
 *
 * **Состояние держит три независимых поля, а не историю нажатий** (PLAN H4). Конвейер один —
 * аптечки, поиск, фильтр, просроченные вперёд, сортировка, — и складывать нажатия в историю
 * значило бы получить от «искал, потом фильтровал» не то же, что от «фильтровал, потом искал».
 *
 * Чем сузить, предлагается по **всей области**, а не по тому, что уже нашлось: иначе выбранная
 * категория исчезла бы из списка, и вернуться к другой было бы нечем.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedKitContentsViewModel @Inject constructor(
    private val removal: MedKitRemoval,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val medKitId: Uuid? =
        savedState.get<String>(RouteArguments.MED_KIT_ID)?.takeIf { it != "null" }?.let(Uuid::parse)

    private val query = MutableStateFlow(PackageQuery(medKitId = medKitId))

    private val removing = MutableStateFlow(Removing())

    private val days = today.observe()

    /** Список вместе с днём, на который он посчитан: просрочка зависит от дня, а не от момента. */
    private val shown = combine(query, days) { query, today -> query to today }
        .flatMapLatest { (query, today) -> packages.list(query, today).map { today to it } }

    /** Вся область без сужений: из неё берутся категории и формы, которыми можно сузить. */
    private val area = days.flatMapLatest { today ->
        packages.list(PackageQuery(medKitId = medKitId), today)
    }

    private val places = days.flatMapLatest { medKits.observeAll(it) }

    val state: StateFlow<MedKitContentsUiState> =
        combine(shown, area, places, query, removing) { (today, shown), area, places, query, removing ->
            val here = places.firstOrNull { it.id == medKitId }
            MedKitContentsUiState(
                medKit = here?.toPresentationDTO(),
                isEverywhere = medKitId == null,
                packages = shown.map { it.toPresentationDTO() },
                // На экране всех лекарств каждая строка называет свою аптечку; внутри одной —
                // не повторяет её: человек и так знает, куда пришёл.
                placeNames = if (medKitId == null) places.associate { it.id to it.name } else emptyMap(),
                categories = area.mapNotNull { it.facts.category }.distinct().sorted(),
                forms = area.mapNotNull { it.facts.form?.toPresentationDTO() }.distinctBy { it.id },
                others = places.filter { it.id != medKitId }.map { it.toPresentationDTO() },
                text = query.text,
                narrowing = query.filter?.asNarrowing(area),
                ordering = query.sort.asOrdering(),
                today = today,
                isLoaded = true,
                removing = removing.step,
                removalRefusal = removing.refusal,
                isRemoved = removing.removed
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedKitContentsUiState())

    fun search(text: String) {
        query.value = query.value.copy(text = text)
    }

    /** Сужение ровно одно: нажатие на выбранное снимает его, иначе выйти из него было бы нечем. */
    fun narrow(narrowing: Narrowing?) {
        query.value = query.value.copy(filter = narrowing?.asFilter())
    }

    fun order(ordering: Ordering) {
        query.value = query.value.copy(sort = ordering.asSort())
    }

    /** Сброс возвращает список и **оставляет область**: человек сбрасывает запрос, а не место. */
    fun reset() {
        query.value = PackageQuery(medKitId = medKitId)
    }

    fun askToRemove() {
        removing.value = Removing(step = RemovalStep.ASKING)
    }

    fun pickTarget() {
        removing.value = Removing(step = RemovalStep.PICKING_TARGET)
    }

    fun dismissRemoval() {
        removing.value = Removing()
    }

    /**
     * Убрать полку. Судьба содержимого — единственное, чем случаи уборки отличаются для человека
     * (PLAN E6), поэтому она и есть аргумент.
     */
    fun remove(transferTo: Uuid? = null) {
        val medKitId = medKitId ?: return
        if (removing.value.step == null) return
        viewModelScope.launch {
            val fate = transferTo?.let(MedKitRemoval.Fate::MoveTo) ?: MedKitRemoval.Fate.ThrowAway
            removing.value = when (removal.remove(medKitId, fate)) {
                MedKitRemoval.Outcome.REMOVED, MedKitRemoval.Outcome.MARKED,
                MedKitRemoval.Outcome.MED_KIT_GONE -> Removing(removed = true)
                MedKitRemoval.Outcome.BUSY -> refused(RemovalRefusal.BUSY)
                MedKitRemoval.Outcome.TARGET_GONE, MedKitRemoval.Outcome.TARGET_IS_THE_SAME ->
                    refused(RemovalRefusal.TARGET_GONE)
                MedKitRemoval.Outcome.TARGET_BUSY -> refused(RemovalRefusal.TARGET_BUSY)
                MedKitRemoval.Outcome.CONTENTS_BUSY -> refused(RemovalRefusal.CONTENTS_BUSY)
                MedKitRemoval.Outcome.NOT_SHARED -> refused(RemovalRefusal.NOT_SHARED)
            }
        }
    }

    private fun refused(refusal: RemovalRefusal) =
        Removing(step = RemovalStep.ASKING, refusal = refusal)

    /** Слова человека — в запрос к базе. Обратный перевод нужен экрану, и он рядом. */
    private fun Narrowing.asFilter(): PackageQuery.Filter = when (this) {
        Narrowing.Expired -> PackageQuery.Filter.Expired
        Narrowing.ExpiringSoon -> PackageQuery.Filter.ExpiringWithin(ExpiryDate.SOON_DAYS)
        Narrowing.OnCourse -> PackageQuery.Filter.OnCourse
        Narrowing.HasFree -> PackageQuery.Filter.HasFree
        is Narrowing.OfCategory -> PackageQuery.Filter.OfCategory(category)
        is Narrowing.OfForm -> PackageQuery.Filter.OfForm(form.id)
    }

    private fun PackageQuery.Filter.asNarrowing(
        area: List<com.kert0n.medapp.domain.pack.PackageProjection>
    ): Narrowing? = when (this) {
        PackageQuery.Filter.Expired -> Narrowing.Expired
        is PackageQuery.Filter.ExpiringWithin -> Narrowing.ExpiringSoon
        PackageQuery.Filter.OnCourse -> Narrowing.OnCourse
        PackageQuery.Filter.HasFree -> Narrowing.HasFree
        is PackageQuery.Filter.OfCategory -> Narrowing.OfCategory(category)
        // Имя формы берётся у той же области, из которой её и выбирали: словарь тут ни при чём.
        is PackageQuery.Filter.OfForm ->
            area.firstNotNullOfOrNull { it.facts.form?.takeIf { form -> form.id == formId } }
                ?.let { Narrowing.OfForm(it.toPresentationDTO()) }
    }

    private fun Ordering.asSort(): PackageQuery.Sort = when (this) {
        Ordering.NAME -> PackageQuery.Sort.NAME
        Ordering.EXPIRY -> PackageQuery.Sort.EXPIRY
        Ordering.ADDED_AT -> PackageQuery.Sort.ADDED_AT
        Ordering.QUANTITY -> PackageQuery.Sort.QUANTITY
    }

    private fun PackageQuery.Sort.asOrdering(): Ordering = when (this) {
        PackageQuery.Sort.NAME -> Ordering.NAME
        PackageQuery.Sort.EXPIRY -> Ordering.EXPIRY
        PackageQuery.Sort.ADDED_AT -> Ordering.ADDED_AT
        PackageQuery.Sort.QUANTITY -> Ordering.QUANTITY
    }

    private data class Removing(
        val step: RemovalStep? = null,
        val refusal: RemovalRefusal? = null,
        val removed: Boolean = false
    )
}

/** На каком шаге разговор об уборке полки. */
enum class RemovalStep { ASKING, PICKING_TARGET }

/** Почему полка не убралась. */
enum class RemovalRefusal { BUSY, TARGET_GONE, TARGET_BUSY, CONTENTS_BUSY, NOT_SHARED }

/** Что показывают экраны содержимого и всех лекарств. */
data class MedKitContentsUiState(
    val medKit: MedKitPresentationDTO? = null,
    val isEverywhere: Boolean = false,
    val packages: List<PackagePresentationDTO> = emptyList(),
    val placeNames: Map<Uuid, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val forms: List<FormPresentationDTO> = emptyList(),
    val others: List<MedKitPresentationDTO> = emptyList(),
    val text: String = "",
    val narrowing: Narrowing? = null,
    val ordering: Ordering = Ordering.NAME,
    val today: LocalDate = LocalDate.MIN,
    val isLoaded: Boolean = false,
    val removing: RemovalStep? = null,
    val removalRefusal: RemovalRefusal? = null,
    /** Полка убрана: экран уходит на список аптечек. */
    val isRemoved: Boolean = false
) {
    /** Сужено ли: от этого зависит, «здесь ничего нет» или «ничего не нашлось». */
    val isNarrowed: Boolean get() = text.isNotBlank() || narrowing != null
}
