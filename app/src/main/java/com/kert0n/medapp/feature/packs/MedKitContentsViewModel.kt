package com.kert0n.medapp.feature.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства сразу (№5) — один экран с двумя областями
 * поиска: аптечка названа или не названа (`PackageQuery.medKitId`). Двух списков не заводится:
 * читают они одно и то же, и разошлись бы при первой же правке одного из них.
 *
 * **Порядок нажатий результат не меняет.** Запрос — три независимых поля, а не история действий:
 * искать и потом фильтровать это то же самое, что фильтровать и потом искать. Конвейер один и
 * живёт в запросе к базе: аптечки, поиск, фильтр, просроченные вперёд, сортировка (PLAN H4).
 *
 * Из чего выбирать категорию и форму, знает сама область, а не выбранное в ней: список этих
 * значений читается без фильтра, иначе выбранная категория осталась бы единственной, и вернуться
 * к другой было бы нечем.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedKitContentsViewModel @Inject constructor(
    private val packages: PackageStorageRepository,
    private val medKits: MedKitStorageRepository,
    private val removal: MedKitRemoval,
    private val clock: Clock
) : ViewModel() {

    /** `null` — экран ещё не открыт: до этого неизвестно даже, чью аптечку читать. */
    private val request = MutableStateFlow<PackageQuery?>(null)

    private val today: LocalDate get() = LocalDate.now(clock)

    /** О чём экран сейчас спрашивает, убирая аптечку; `null` — не спрашивает ни о чём. */
    private val removing = MutableStateFlow<Removing?>(null)

    val state: StateFlow<State> = request.filterNotNull()
        .flatMapLatest { query ->
            combine(
                medKits.observeAll(today),
                packages.list(query, today),
                choicesOf(query.medKitId),
                removing
            ) { kits, packs, choices, removing ->
                State(
                    medKit = kits.firstOrNull { it.id == query.medKitId }?.toPresentationDTO(),
                    everywhere = query.medKitId == null,
                    packs = packs.map { it.toPresentationDTO() },
                    // Из какой аптечки пачка, нужно только там, где их много (экран 5).
                    medKitNames = if (query.medKitId != null) emptyMap()
                    else kits.associate { it.id to it.name },
                    categories = choices.categories,
                    forms = choices.forms,
                    query = query,
                    today = today,
                    // Куда переносить: местные аптечки, кроме этой. Общая требует связи (C3).
                    others = kits
                        .filter { it.id != query.medKitId && it.publication == MedKit.Publication.LOCAL }
                        .map { it.toPresentationDTO() },
                    removing = removing
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /** [medKitId] `null` — ищем по всем доступным аптечкам (экран 5). */
    fun open(medKitId: Uuid?) {
        if (request.value != null) return
        request.value = PackageQuery(medKitId = medKitId)
    }

    fun search(text: String) = request.update { it?.copy(text = text) }

    /** Фильтр ровно один: два одновременных сузили бы список до пустого чаще, чем помогли (H4). */
    fun filter(filter: PackageQuery.Filter?) = request.update { it?.copy(filter = filter) }

    fun sort(sort: PackageQuery.Sort) = request.update { it?.copy(sort = sort) }

    /** Спросить, убирать ли аптечку: пустую — просто подтвердить, непустую — выбрать судьбу. */
    fun askToRemove() {
        removing.value = Removing.Asking
    }

    /** Выбор аптечки назначения — отдельный шаг: «перенести» без «куда» не бывает. */
    fun pickTarget() {
        removing.value = Removing.PickingTarget()
    }

    fun chooseTarget(medKitId: Uuid) {
        removing.value = Removing.PickingTarget(medKitId)
    }

    fun dismissRemoval() {
        removing.value = null
    }

    /**
     * Убрать аптечку. [transferTo] `null` — выбросить вместе с лекарствами. [onRemoved] зовётся
     * только когда убрано: отказ остаётся на экране названной причиной.
     */
    fun remove(transferTo: Uuid? = null, onRemoved: () -> Unit) {
        val medKitId = request.value?.medKitId ?: return
        viewModelScope.launch {
            when (val outcome = removal.remove(medKitId, transferTo)) {
                MedKitRemoval.Outcome.REMOVED -> {
                    removing.value = null
                    onRemoved()
                }
                else -> removing.value = Removing.Refused(outcome)
            }
        }
    }

    /** Сбросить: запрос ни при чём, если человек просто не нашёл нужного. */
    fun reset() = request.update { it?.let { query -> PackageQuery(medKitId = query.medKitId) } }

    /**
     * Что вообще есть в этой области — из чего предлагать категорию и форму. Читается без поиска
     * и фильтра, поэтому набор не схлопывается вслед за выбором.
     */
    private fun choicesOf(medKitId: Uuid?) =
        packages.list(PackageQuery(medKitId = medKitId), today).map { packs ->
            Choices(
                categories = packs.mapNotNull { it.facts.category }.distinct().sorted(),
                forms = packs.mapNotNull { it.facts.form }
                    .distinctBy { it.id }
                    .map { it.toPresentationDTO() }
                    .sortedBy { it.name }
            )
        }

    /**
     * Что показывает экран. `packs == null` — чтение ещё не пришло: показывать «пусто» рано, это
     * было бы неправдой. [everywhere] — экран 5: аптечка не названа, и у строк видно, чья пачка.
     */
    data class State(
        val medKit: MedKitPresentationDTO? = null,
        val everywhere: Boolean = false,
        val packs: List<PackagePresentationDTO>? = null,
        val medKitNames: Map<Uuid, String> = emptyMap(),
        val categories: List<String> = emptyList(),
        val forms: List<FormPresentationDTO> = emptyList(),
        val query: PackageQuery = PackageQuery(),
        /** Просрочка считается на сегодня, и сегодня знают часы, а не база. */
        val today: LocalDate = LocalDate.MIN,
        val others: List<MedKitPresentationDTO> = emptyList(),
        val removing: Removing? = null
    ) {
        /** Ищут или сужают — значит пустота значит «не нашлось», а не «здесь ничего нет». */
        val isNarrowed: Boolean get() = query.searchText.isNotEmpty() || query.filter != null
    }

    /**
     * Шаги разговора об удалении аптечки (PLAN H3). Каждый — свой вопрос человеку, и различает
     * их то, что он в этот момент выбирает.
     */
    sealed interface Removing {

        /** Убирать ли вообще: у пустой это весь разговор. */
        data object Asking : Removing

        /** Куда перенести лекарства; `null` — ещё не выбрано. */
        data class PickingTarget(val target: Uuid? = null) : Removing

        /** Убрать не вышло, и сказано почему. */
        data class Refused(val reason: MedKitRemoval.Outcome) : Removing
    }

    private data class Choices(
        val categories: List<String>,
        val forms: List<FormPresentationDTO>
    )
}
