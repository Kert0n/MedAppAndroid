package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства сразу (№5). Экран один, областей две: аптечка
 * названа или не названа, и это единственное, чем они различаются.
 *
 * **Состояние держит три независимых поля, а не историю нажатий** (PLAN H4). Конвейер один —
 * аптечки, поиск, сужение, просроченные вперёд, порядок, — и складывать нажатия в историю
 * значило бы получить от «искал, потом сузил» не то же, что от «сузил, потом искал».
 *
 * Чем сузить, предлагается по **всей области**, а не по тому, что уже нашлось: иначе выбранная
 * категория исчезла бы из списка, и вернуться к другой было бы нечем.
 *
 * Названная полка при открытии перечитывается, и пока ответ не пришёл, экран ждёт (PLAN E4): без
 * связи и у местной полки ждать нечего, и дверь возвращается сразу.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MedKitContentsViewModel.Factory::class)
class MedKitContentsViewModel @AssistedInject constructor(
    private val removal: MedKitRemoval,
    freshening: Freshening,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    courses: CourseStorageRepository,
    today: Today,
    @Assisted private val medKitId: Uuid?
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(medKitId: Uuid?): MedKitContentsViewModel
    }

    private val query = MutableStateFlow(PackageQuery(medKitId = medKitId))

    private val removing = MutableStateFlow(Removing())

    /** Перечитывание при открытии кончилось; у «всех лекарств» его нет вовсе. */
    private val freshened = MutableStateFlow(medKitId == null)

    init {
        if (medKitId != null) {
            viewModelScope.launch {
                try {
                    freshening.medKit(medKitId)
                } finally {
                    freshened.value = true
                }
            }
        }
    }

    /** Запрос вместе с тем, дождались ли ответа сервера: типизированный `combine` дальше пяти потоков не идёт. */
    private val asked = combine(query, freshened) { query, freshened -> query to freshened }

    private val days = today.observe().map { it.date }

    /** Список вместе с днём, на который он посчитан: просрочка зависит от дня, а не от момента. */
    private val shown = combine(query, days) { query, today -> query to today }
        .flatMapLatest { (query, today) -> packages.list(query, today).map { today to it } }

    /** Вся область без сужений: из неё берутся категории и формы, которыми можно сузить. */
    private val area = days.flatMapLatest { today -> packages.list(PackageQuery(medKitId = medKitId), today) }

    private val places = days.flatMapLatest { medKits.observeAll(it) }

    /**
     * Лечения, которые потеряют источники, если полку убрать: их держат коробки **этой** полки.
     * Своего чтения для этого не нужно — у проекции коробки уже есть держащее лечение, а имя ему
     * даёт запись эпизода (PLAN U2 строка 23).
     */
    private val records = courses.observeRecords()

    /** Уборка вместе с тем, что она заденет: типизированный `combine` дальше пяти потоков не идёт. */
    private val removalAsked = combine(removing, records) { removing, records -> removing to records }

    val state: StateFlow<MedKitContentsUiState> =
        combine(shown, area, places, asked, removalAsked) { (today, shown), area, places, (query, freshened), (removing, records) ->
            val here = places.firstOrNull { it.id == medKitId }
            val held = area.mapNotNull { it.holdingCourseId }.toSet()
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
                isAreaEmpty = area.isEmpty(),
                text = query.text,
                narrowing = query.filter?.asNarrowing(area),
                ordering = query.sort.asOrdering(),
                today = today,
                isLoaded = freshened,
                removing = removing.step,
                removalRefusal = removing.refusal,
                isRemoved = removing.removed,
                affectedCourses = records.filter { it.isOpen && it.id in held }.map { it.title }
            )
        }
            // Сборка состояния на тысяче коробок стоит около 90 мс (J1) — на главном потоке это
            // пять кадров при каждом открытии. Считается вне его, показывается на нём.
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                // Область известна до первого чтения: она пришла ключом. Не сказать её сразу —
                // показать на миг чужой заголовок.
                MedKitContentsUiState(isEverywhere = medKitId == null)
            )

    fun search(text: String) {
        query.value = query.value.copy(text = text)
    }

    /** Сужение ровно одно: нажатие на выбранное снимает его, иначе выйти из него нечем. */
    fun narrow(narrowing: Narrowing?) {
        query.value = query.value.copy(filter = narrowing?.asFilter())
    }

    fun order(ordering: Ordering) {
        query.value = query.value.copy(sort = ordering.asSort())
    }

    /** Сброс оставляет область: человек сбрасывает запрос, а не место. */
    fun reset() {
        query.value = PackageQuery(medKitId = medKitId)
    }

    fun askToRemove() {
        if (removing.value.working) return
        removing.value = Removing(step = RemovalStep.ASKING)
    }

    fun pickTarget() {
        if (removing.value.working) return
        removing.value = Removing(step = RemovalStep.PICKING_TARGET)
    }

    fun dismissRemoval() {
        if (removing.value.working) return
        removing.value = Removing()
    }

    /**
     * Убрать полку. Судьба содержимого — единственное, чем случаи уборки отличаются для
     * человека (PLAN E6), поэтому она и есть аргумент. Второе решение поверх первого не
     * начинается: признак работы ставится до обращения к сценарию.
     */
    fun remove(transferTo: Uuid? = null) {
        decide(transferTo?.let(MedKitRemoval.Fate::MoveTo) ?: MedKitRemoval.Fate.ThrowAway)
    }

    /**
     * Выйти и оставить полку остальным (PLAN E6): коробки живут у них, у нас они потеряны.
     * Лечения при этом остаются — теряются только источники с этой полки (C1 «Курсы при выходе»).
     */
    fun leave() {
        decide(MedKitRemoval.Fate.LeaveToOthers)
    }

    private fun decide(fate: MedKitRemoval.Fate) {
        val medKitId = medKitId ?: return
        val now = removing.value
        if (now.step == null || now.working) return
        removing.value = now.copy(working = true)
        viewModelScope.launch {
            removing.value = when (removal.remove(medKitId, fate)) {
                MedKitRemoval.Outcome.REMOVED, MedKitRemoval.Outcome.MARKED,
                MedKitRemoval.Outcome.MED_KIT_GONE -> Removing(removed = true)
                MedKitRemoval.Outcome.BUSY -> refused(RemovalRefusal.BUSY)
                // Отказала цель — человек выбирает другую там же, где выбирал эту.
                MedKitRemoval.Outcome.TARGET_GONE, MedKitRemoval.Outcome.TARGET_IS_THE_SAME ->
                    refused(RemovalRefusal.TARGET_GONE, now.step)
                MedKitRemoval.Outcome.TARGET_BUSY -> refused(RemovalRefusal.TARGET_BUSY, now.step)
                MedKitRemoval.Outcome.CONTENTS_BUSY -> refused(RemovalRefusal.CONTENTS_BUSY)
                MedKitRemoval.Outcome.NOT_SHARED -> refused(RemovalRefusal.NOT_SHARED)
            }
        }
    }

    private fun refused(refusal: RemovalRefusal, step: RemovalStep = RemovalStep.ASKING) =
        Removing(step = step, refusal = refusal)

    private fun Narrowing.asFilter(): PackageQuery.Filter = when (this) {
        Narrowing.Expired -> PackageQuery.Filter.Expired
        Narrowing.ExpiringSoon -> PackageQuery.Filter.ExpiringWithin(ExpiryDate.SOON_DAYS)
        Narrowing.OnCourse -> PackageQuery.Filter.OnCourse
        Narrowing.HasFree -> PackageQuery.Filter.HasFree
        is Narrowing.OfCategory -> PackageQuery.Filter.OfCategory(category)
        is Narrowing.OfForm -> PackageQuery.Filter.OfForm(form.id)
    }

    /**
     * Обратно: чем список сужен сейчас. Форма называется именем, а не тождеством — человеку
     * показывают её, а имя берётся из той же области, из которой предлагали выбирать.
     */
    private fun PackageQuery.Filter.asNarrowing(area: List<PackageProjection>): Narrowing? = when (this) {
        PackageQuery.Filter.Expired -> Narrowing.Expired
        is PackageQuery.Filter.ExpiringWithin -> Narrowing.ExpiringSoon
        PackageQuery.Filter.OnCourse -> Narrowing.OnCourse
        PackageQuery.Filter.HasFree -> Narrowing.HasFree
        is PackageQuery.Filter.OfCategory -> Narrowing.OfCategory(category)
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
        /** Решение уже отдано сценарию: второго такого же не начинается. */
        val working: Boolean = false,
        val refusal: RemovalRefusal? = null,
        val removed: Boolean = false
    )
}

/** На каком шаге разговор об уборке полки. */
enum class RemovalStep { ASKING, PICKING_TARGET }

/** Почему полка не убралась. */
enum class RemovalRefusal { BUSY, TARGET_GONE, TARGET_BUSY, CONTENTS_BUSY, NOT_SHARED }

/** Что показывает экран содержимого. */
data class MedKitContentsUiState(
    val medKit: MedKitPresentationDTO? = null,
    val isEverywhere: Boolean = false,
    val packages: List<PackagePresentationDTO> = emptyList(),
    val placeNames: Map<Uuid, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val forms: List<FormPresentationDTO> = emptyList(),
    val others: List<MedKitPresentationDTO> = emptyList(),
    /** В области нет ни одной коробки — это «пусто», а не «ничего не нашлось». */
    val isAreaEmpty: Boolean = false,
    val text: String = "",
    val narrowing: Narrowing? = null,
    val ordering: Ordering = Ordering.NAME,
    val today: LocalDate = LocalDate.MIN,
    val isLoaded: Boolean = false,
    val removing: RemovalStep? = null,
    val removalRefusal: RemovalRefusal? = null,
    val isRemoved: Boolean = false,
    /** Лечения, которые потеряют источники вместе с полкой. Сами лечения остаются (PLAN C1). */
    val affectedCourses: List<String> = emptyList()
) {
    /** Искал или сужал: «ничего не нашлось» — это не «здесь пусто». */
    val isNarrowed: Boolean get() = text.isNotBlank() || narrowing != null

    /**
     * Полки у нас больше нет: её убрали у всех или нас вывели (PLAN E6), и перечитывание при открытии
     * это записало. Заводить в неё нечего. Убрал её сам человек — это [isRemoved], и экран уходит.
     */
    val isShelfGone: Boolean get() = isLoaded && !isEverywhere && medKit == null && !isRemoved
}
