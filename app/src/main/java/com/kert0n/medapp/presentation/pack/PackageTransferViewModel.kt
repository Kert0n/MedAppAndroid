package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
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
 * Перенос упаковки в другую аптечку (PLAN H3 №11). Перенос двигает место, а не остаток: из
 * коробки при этом ничего не расходуется.
 *
 * Нынешняя полка среди мест не предлагается: класть коробку туда, где она уже лежит, нечем — и
 * сценарий отвечает на это отдельным исходом, который человеку видеть незачем, если выбора ему
 * и не давали.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PackageTransferViewModel @Inject constructor(
    private val relocation: PackageRelocation,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val packageId: Uuid =
        Uuid.parse(checkNotNull(savedState[RouteArguments.PACKAGE_ID]) { "маршрут переноса называет коробку" })

    private val chosen = MutableStateFlow<Uuid?>(null)

    private val progress = MutableStateFlow(Progress())

    val state: StateFlow<PackageTransferUiState> = combine(
        packages.observe(packageId),
        today.observe().flatMapLatest { medKits.observeAll(it) },
        chosen,
        progress
    ) { pack, medKits, chosen, progress ->
        PackageTransferUiState(
            // Нынешняя полка не предлагается: перенести коробку туда, где она лежит, нельзя.
            places = medKits.filter { it.id != pack?.medKit?.id }.map { it.toPresentationDTO() },
            chosen = chosen,
            isGone = pack == null,
            refusal = progress.refusal,
            isDone = progress.done,
            isLoading = pack == null && !progress.done
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageTransferUiState())

    fun choose(medKitId: Uuid) {
        chosen.value = medKitId
        if (progress.value.refusal != null) progress.value = Progress()
    }

    /** Пока место не выбрано, не пишется ничего: переносить некуда — значит и действия нет. */
    fun transfer() {
        val target = chosen.value ?: return
        val now = progress.value
        if (now.working || now.done) return
        progress.value = Progress(working = true)
        viewModelScope.launch {
            progress.value = when (relocation.move(packageId, target)) {
                // Переехала или поехала: в обоих случаях решение принято, и экран уходит.
                PackageRelocation.Outcome.MOVED, PackageRelocation.Outcome.MARKED -> Progress(done = true)
                PackageRelocation.Outcome.GONE -> Progress(refusal = TransferRefusal.PACKAGE_GONE)
                PackageRelocation.Outcome.UNUSABLE -> Progress(refusal = TransferRefusal.PACKAGE_BUSY)
                PackageRelocation.Outcome.ORIGIN_BUSY -> Progress(refusal = TransferRefusal.ORIGIN_BUSY)
                PackageRelocation.Outcome.TARGET_GONE -> Progress(refusal = TransferRefusal.TARGET_GONE)
                PackageRelocation.Outcome.TARGET_BUSY -> Progress(refusal = TransferRefusal.TARGET_BUSY)
                // Та же полка: выбора такого не давали, и если он всё же случился — это та же
                // «выберите другую», а не отдельный рассказ.
                PackageRelocation.Outcome.TARGET_IS_THE_SAME -> Progress(refusal = TransferRefusal.TARGET_GONE)
            }
        }
    }

    private data class Progress(
        val working: Boolean = false,
        val refusal: TransferRefusal? = null,
        val done: Boolean = false
    )
}

/**
 * Почему перенос не состоялся. Исход сценария сюда не проходит: экран о `feature/` не знает
 * (граница H1), а человеку нужно знать, ждать ему или выбирать другое место.
 */
enum class TransferRefusal {

    /** Коробки больше нет. */
    PACKAGE_GONE,

    /** Коробка ждёт ответа сервера на решение о себе. */
    PACKAGE_BUSY,

    /** Полка, с которой несут, ждёт ответа на своё решение (PLAN E5). */
    ORIGIN_BUSY,

    /** Выбранной полки больше нет — выбрать надо другую. */
    TARGET_GONE,

    /** Выбранная полка ждёт ответа на своё решение: класть в неё рано. */
    TARGET_BUSY
}

/** Что показывает экран переноса. */
data class PackageTransferUiState(
    val places: List<MedKitPresentationDTO> = emptyList(),
    val chosen: Uuid? = null,
    val isGone: Boolean = false,
    val refusal: TransferRefusal? = null,
    val isDone: Boolean = false,
    val isLoading: Boolean = true
)
