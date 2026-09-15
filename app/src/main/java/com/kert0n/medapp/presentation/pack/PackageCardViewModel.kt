package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Карточка упаковки (PLAN H3 №6). Первым — сколько есть; остальное человек читает вторым
 * взглядом.
 *
 * Имя полки и название держащего лечения — чужие агрегаты: в проекции коробки лежат их
 * тождества, а слова берутся чтениями. **Полка читается одна, а не все**: ради одного имени не
 * спрашивают `GROUP BY` по всем коробкам базы.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PackageCardViewModel.Factory::class)
class PackageCardViewModel @AssistedInject constructor(
    private val removal: PackageRemoval,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    courses: CourseStorageRepository,
    today: Today,
    @Assisted private val packageId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): PackageCardViewModel
    }

    private val removing = MutableStateFlow(Removing())

    private val days = today.observe()

    private val pack = packages.observe(packageId)

    private val place = combine(pack, days) { pack, day -> pack?.medKit?.id to day.date }
        .distinctUntilChanged()
        .flatMapLatest { (medKitId, today) -> medKitId?.let { medKits.observe(it, today) } ?: flowOf(null) }

    private val course = pack.map { it?.holdingCourseId }
        .distinctUntilChanged()
        .flatMapLatest { courseId -> courseId?.let(courses::observeRecord) ?: flowOf(null) }

    val state: StateFlow<PackageCardUiState> = combine(pack, place, course, days, removing) { pack, place, course, day, removing ->
        PackageCardUiState(
            pack = pack?.toPresentationDTO(),
            medKitName = place?.name,
            holdingCourseTitle = course?.title,
            lastUsedOn = pack?.lastUsedAt?.atZone(day.zone)?.toLocalDate(),
            today = day.date,
            // «Нет» говорится только после чтения: до него это состояние сюда не доходит.
            isGone = pack == null,
            asksToRemove = removing.asking,
            isBusy = removing.busy,
            isRemoved = removing.removed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageCardUiState())

    fun askToRemove() {
        if (removing.value.working) return
        removing.value = Removing(asking = true)
    }

    fun dismissRemoval() {
        if (removing.value.working) return
        removing.value = Removing()
    }

    /**
     * Выбросить. Зовётся только после подтверждения; признак работы ставится до обращения к
     * сценарию, и второе нажатие ничего не начинает. Убрана или уже убрана — уходим; помечена —
     * карточка остаётся, и о пометке говорит сама коробка своим статусом; занята другим
     * решением — сказано, и ничего не изменилось.
     */
    fun remove() {
        val now = removing.value
        if (!now.asking || now.working) return
        removing.value = now.copy(working = true)
        viewModelScope.launch {
            removing.value = when (removal.remove(packageId)) {
                PackageRemoval.Outcome.REMOVED, PackageRemoval.Outcome.GONE -> Removing(removed = true)
                PackageRemoval.Outcome.MARKED -> Removing()
                PackageRemoval.Outcome.UNUSABLE -> Removing(busy = true)
            }
        }
    }

    private data class Removing(
        val asking: Boolean = false,
        /** Решение уже отдано сценарию: второго такого же не начинается. */
        val working: Boolean = false,
        val busy: Boolean = false,
        val removed: Boolean = false
    )
}

/**
 * Что показывает карточка. [isGone] и [isRemoved] различаются тем, что делает человек: первое
 * — коробки не стало, пока карточка была открыта, и он читает об этом; второе — он сам её
 * выбросил, и экран уходит. [isBusy] — отказ: коробка ждёт ответа на другое решение (PLAN E1).
 */
data class PackageCardUiState(
    val pack: PackagePresentationDTO? = null,
    val medKitName: String? = null,
    val holdingCourseTitle: String? = null,
    /** Когда из коробки брали последний раз — днём в зоне человека, как он это помнит. */
    val lastUsedOn: LocalDate? = null,
    val today: LocalDate = LocalDate.MIN,
    val isGone: Boolean = false,
    val asksToRemove: Boolean = false,
    val isBusy: Boolean = false,
    val isRemoved: Boolean = false
) {
    /** Пока первое чтение не пришло, карточка не говорит ни «есть», ни «нет». */
    val isLoading: Boolean get() = pack == null && !isGone
}
