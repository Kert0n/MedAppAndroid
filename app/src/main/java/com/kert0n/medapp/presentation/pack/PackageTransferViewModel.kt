package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Перенос упаковки на другую полку (PLAN H3 №11). Перенос двигает место, а не остаток: из
 * коробки при этом ничего не расходуется.
 *
 * Нынешняя полка среди мест не предлагается: класть коробку туда, где она уже лежит, нечем.
 * Общие полки предлагаются наравне с местными — аптечки доступны всегда, а что при этом едет
 * серверу, решает сценарий (C3, E6).
 *
 * Коробка общей полки при открытии перечитывается, и пока сервер не ответил, экран ждёт: чужие
 * брони на ней решают, предупреждать ли о переносе (PLAN E4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PackageTransferViewModel.Factory::class)
class PackageTransferViewModel @AssistedInject constructor(
    private val relocation: PackageRelocation,
    freshening: Freshening,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    @Assisted private val packageId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): PackageTransferViewModel
    }

    private val chosen = MutableStateFlow<Uuid?>(null)

    private val progress = MutableStateFlow(Progress())

    /** Перечитывание коробки при открытии кончилось (PLAN E4). */
    private val freshened = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                freshening.pack(packageId)
            } finally {
                freshened.value = true
            }
        }
    }

    val state: StateFlow<PackageTransferUiState> = combine(
        packages.observe(packageId),
        today.observe().flatMapLatest { medKits.observeAll(it.date) },
        chosen,
        progress,
        freshened
    ) { pack, kits, chosen, progress, freshened ->
        PackageTransferUiState(
            places = kits.filter { it.id != pack?.medKit?.id }.map { it.toPresentationDTO() },
            chosen = chosen,
            // Перенос может лишить другого участника доступа: сервер сохранит его бронь, только
            // если он видит целевую полку, а видит ли — знает он, а не мы (PLAN E6). Поэтому
            // предупреждение общее и стоит **до** подтверждения, а не после.
            hasClaimsOfOthers = pack?.availability?.reservedByOthers?.isZero == false,
            isGone = pack == null,
            refusal = progress.refusal,
            isDone = progress.done,
            isLoaded = freshened
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageTransferUiState())

    /** Выбор снимает прежний отказ: человек уже отвечает на него. */
    fun choose(medKitId: Uuid) {
        chosen.value = medKitId
        if (progress.value.refusal != null) progress.value = Progress()
    }

    /**
     * Перенести. Пока место не выбрано, не пишется ничего: переносить «куда-нибудь» не бывает.
     * Замок ставится до обращения к сценарию; переехала или поехала — решение принято, и экран
     * уходит; отказ — причиной, и коробка на месте.
     */
    fun transfer() {
        val target = chosen.value ?: return
        val now = progress.value
        if (now.working || now.done) return
        progress.value = Progress(working = true)
        viewModelScope.launch {
            progress.value = when (relocation.move(packageId, target)) {
                PackageRelocation.Outcome.MOVED, PackageRelocation.Outcome.MARKED -> Progress(done = true)
                PackageRelocation.Outcome.GONE -> Progress(refusal = PackageTransferRefusal.PACKAGE_GONE)
                PackageRelocation.Outcome.UNUSABLE -> Progress(refusal = PackageTransferRefusal.PACKAGE_BUSY)
                PackageRelocation.Outcome.ORIGIN_BUSY -> Progress(refusal = PackageTransferRefusal.ORIGIN_BUSY)
                PackageRelocation.Outcome.TARGET_BUSY -> Progress(refusal = PackageTransferRefusal.TARGET_BUSY)
                // Та же полка: такого выбора не давали, и если он всё же случился — это та же
                // «выберите другую», а не отдельный рассказ.
                PackageRelocation.Outcome.TARGET_GONE, PackageRelocation.Outcome.TARGET_IS_THE_SAME ->
                    Progress(refusal = PackageTransferRefusal.TARGET_GONE)
            }
        }
    }

    private data class Progress(
        val working: Boolean = false,
        val refusal: PackageTransferRefusal? = null,
        val done: Boolean = false
    )
}

/**
 * Почему перенос не состоялся. Исход сценария сюда не проходит: экран о `feature/` не знает
 * (граница H1), а человеку нужно знать, ждать ему или выбирать другое место.
 */
enum class PackageTransferRefusal {

    /** Коробки больше нет. */
    PACKAGE_GONE,

    /** Коробка ждёт ответа сервера на решение о себе (PLAN E1). */
    PACKAGE_BUSY,

    /** Полка, с которой несут, ждёт ответа на своё решение (PLAN E5). */
    ORIGIN_BUSY,

    /** Выбранной полки больше нет — выбрать надо другую. */
    TARGET_GONE,

    /** Выбранная полка ждёт ответа на своё решение: класть в неё рано. */
    TARGET_BUSY
}

/** Что показывает экран переноса. [isLoaded] — первое чтение пришло: до него «пусто» — неправда. */
data class PackageTransferUiState(
    val places: List<MedKitPresentationDTO> = emptyList(),
    val chosen: Uuid? = null,
    /** На коробку заявили другие: перенос может отнять у них бронь (PLAN E6). */
    val hasClaimsOfOthers: Boolean = false,
    val isGone: Boolean = false,
    val refusal: PackageTransferRefusal? = null,
    val isDone: Boolean = false,
    val isLoaded: Boolean = false
)
