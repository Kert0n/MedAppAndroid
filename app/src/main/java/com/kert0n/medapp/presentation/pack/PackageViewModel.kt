package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Карточка упаковки (PLAN H3 №6). Первым — сколько есть; остальное человек читает вторым
 * взглядом.
 *
 * Название держащего лечения и название аптечки экран берёт **чтениями**, а не из проекции
 * пачки: коробка о них не знает, и знать не должна — это чужие агрегаты, и в проекции лежат их
 * тождества (PLAN D4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PackageViewModel @Inject constructor(
    private val removal: PackageRemoval,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    courses: CourseStorageRepository,
    today: Today,
    savedState: SavedStateHandle
) : ViewModel() {

    private val packageId: Uuid =
        Uuid.parse(checkNotNull(savedState[RouteArguments.PACKAGE_ID]) { "маршрут карточки называет коробку" })

    private val removing = MutableStateFlow(Removing())

    private val projection = packages.observe(packageId)

    private val today = today.observe()

    val state: StateFlow<PackageUiState> = combine(
        projection,
        projection.flatMapLatest { it?.holdingCourseId?.let(courses::observeRecord) ?: flowOf(null) },
        // Полки читаются ради имени, но день всё равно нужен порту, и подсунуть ему выдуманный
        // нельзя: чтение одно на всех, и оно считает по названному дню.
        this.today.flatMapLatest { day -> medKits.observeAll(day) }
            .map { kits -> kits.associate { it.id to it.name } },
        this.today,
        removing
    ) { pack, course, names, today, removing ->
        PackageUiState(
            pack = pack?.toPresentationDTO(),
            holdingCourseTitle = course?.title,
            medKitName = pack?.let { names[it.medKit.id] },
            today = today,
            isGone = pack == null,
            asksToRemove = removing.asking,
            refusal = removing.refusal,
            isRemoved = removing.removed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageUiState())

    fun askToRemove() {
        if (removing.value.working) return
        removing.value = Removing(asking = true)
    }

    fun dismissRemoval() {
        if (removing.value.working) return
        removing.value = Removing()
    }

    /**
     * Удаление — опасное действие, и зовётся оно только после подтверждения (PLAN H3). Пока
     * первое удаление в пути, второго не начинается: разговор больше не открывается, и ответ на
     * второе не подменяет собой ответ на первое — «удаление в пути» иначе сменялось бы на
     * «коробка занята», хотя занята она этим же удалением.
     */
    fun remove() {
        val now = removing.value
        if (!now.asking || now.working) return
        removing.value = Removing(working = true)
        viewModelScope.launch {
            removing.value = when (removal.remove(packageId)) {
                // Удалена или уже удалена — карточке показывать нечего: уходим.
                PackageRemoval.Outcome.REMOVED, PackageRemoval.Outcome.GONE -> Removing(removed = true)
                // Решение принято, а полка ещё не ответила: карточка остаётся и говорит об этом.
                PackageRemoval.Outcome.MARKED -> Removing(refusal = PackageRefusal.REMOVAL_ON_THE_WAY)
                PackageRemoval.Outcome.UNUSABLE -> Removing(refusal = PackageRefusal.BUSY)
            }
        }
    }

    private data class Removing(
        val asking: Boolean = false,
        /** Решение уже отдано сценарию: второго такого же не начинается. */
        val working: Boolean = false,
        val refusal: PackageRefusal? = null,
        val removed: Boolean = false
    )
}

/**
 * Почему с коробкой сейчас ничего не сделать. Исход сценария сюда не проходит: экран о
 * `feature/` не знает (граница H1), а человеку всё равно, как называется исход, — ему нужно
 * знать, ждать или нельзя. Текст по причине берёт экран из `R.string.*`.
 */
enum class PackageRefusal {

    /** Решение принято, общая полка ещё не ответила: коробка на месте и помечена. */
    REMOVAL_ON_THE_WAY,

    /** Коробка ждёт ответа на другое решение: второе поверх первого деть некуда (PLAN E1). */
    BUSY
}

/**
 * Что показывает карточка. [isGone] и [isRemoved] различаются тем, что человек делает: первое —
 * коробки не стало, пока карточка была открыта, и он читает об этом; второе — он сам её удалил,
 * и экран уходит.
 */
data class PackageUiState(
    val pack: PackagePresentationDTO? = null,
    val holdingCourseTitle: String? = null,
    val medKitName: String? = null,
    /** До первого чтения день неизвестен, и просроченного ещё нет: `MIN`, а не `EPOCH` — тот
     * появился только в API 34, а мы живём с 29 (PLAN H2). */
    val today: LocalDate = LocalDate.MIN,
    val isGone: Boolean = false,
    val asksToRemove: Boolean = false,
    val refusal: PackageRefusal? = null,
    val isRemoved: Boolean = false
) {
    /** Пока первое чтение не пришло, карточка не говорит ни «есть», ни «нет». */
    val isLoading: Boolean get() = pack == null && !isGone
}
