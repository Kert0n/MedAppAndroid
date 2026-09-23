package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.ScreenReading
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.operation.SyncOperationStorageRepository
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
import com.kert0n.medapp.presentation.Fresh
import com.kert0n.medapp.presentation.readAfter

/**
 * Карточка упаковки (PLAN H3 №6). Первым — сколько есть; остальное человек читает вторым
 * взглядом.
 *
 * Имя полки и название держащего лечения — чужие агрегаты: в проекции коробки лежат их
 * тождества, а слова берутся чтениями. **Полка читается одна, а не все**: ради одного имени не
 * спрашивают `GROUP BY` по всем коробкам базы.
 *
 * Коробка при открытии перечитывается, и пока ответ не пришёл, карточка ждёт: число, по которому
 * человек решает, должно быть свежим (PLAN E4). Без связи и у местной коробки ждать нечего.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PackageCardViewModel.Factory::class)
class PackageCardViewModel @AssistedInject constructor(
    private val removal: PackageRemoval,
    freshening: Freshening,
    packages: PackageStorageRepository,
    medKits: MedKitStorageRepository,
    courses: CourseStorageRepository,
    operations: SyncOperationStorageRepository,
    today: Today,
    @Assisted private val packageId: Uuid,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): PackageCardViewModel
    }

    private val removing = MutableStateFlow(Removing())

    /** Коробка, прочитанная после перечитывания (PLAN E4): до него — ожидание. */
    private val fresh = viewModelScope.readAfter(reading, { freshening.pack(packageId) }) { packages.observe(packageId) }

    private val days = today.observe()

    private val pack = fresh.map { (it as? Fresh.Read)?.value }

    /** Коробка, как её прочитали, — вместе с тем, дождались ли ответа сервера. */
    private val read = fresh.map { (it as? Fresh.Read)?.value to (it is Fresh.Read) }

    /**
     * Место коробки вместе с тем, о **какой** коробке оно прочитано. Пока читается полка новой
     * коробки, в руках остаётся ответ о прежней — а его место этой коробке не принадлежит.
     */
    private val place = combine(pack, days) { pack, day -> pack?.medKit?.id to day.date }
        .distinctUntilChanged()
        .flatMapLatest { (medKitId, today) ->
            medKitId?.let { id -> medKits.observe(id, today).map { Place(id, it) } } ?: flowOf(Place())
        }

    private val course = pack.map { it?.holdingCourseId }
        .distinctUntilChanged()
        .flatMapLatest { courseId -> courseId?.let(courses::observeRecord) ?: flowOf(null) }

    /**
     * Отказ сервера **об этой коробке**: расхождение по числу лечится пересчётом, и путь к нему
     * начинается там, где человек о расхождении узнаёт (PLAN E3, REQ-045). Спор о сведениях сюда
     * не попадает — пересчётом он не лечится.
     */
    private val refused = operations.observeTroubles()
        .map { troubles -> troubles.any { it.recountable == packageId } }
        .distinctUntilChanged()

    private val seenAndRefused = combine(days, refused) { day, refused -> day to refused }

    val state: StateFlow<PackageCardUiState> = combine(read, place, course, seenAndRefused, removing) { (pack, freshened), place, course, (day, refused), removing ->
        PackageCardUiState(
            isFreshening = !freshened,
            pack = pack?.toPresentationDTO(),
            medKitName = place.of(pack)?.name,
            holdingCourseTitle = course?.title,
            lastUsedOn = pack?.lastUsedAt?.atZone(day.zone)?.toLocalDate(),
            today = day.date,
            isRefusedByServer = refused,
            // «Нет» говорится только после чтения — и базы, и сервера: пока ждём ответа, коробка,
            // которой в базе нет, ещё может оказаться (PLAN H3 «Непрочитанное не выдаётся за исчезнувшее»).
            isPlaceUnread = pack != null && !place.isOf(pack),
            isGone = freshened && pack == null,
            asksToRemove = removing.asking,
            isBusy = removing.busy,
            isRemoved = removing.removed
        )
    }.stateInScreen(viewModelScope, reading, PackageCardUiState())

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
        act(failures, undo = { removing.value = now }) {
            removing.value = when (removal.remove(packageId)) {
                PackageRemoval.Outcome.REMOVED, PackageRemoval.Outcome.GONE -> Removing(removed = true)
                PackageRemoval.Outcome.MARKED -> Removing()
                PackageRemoval.Outcome.UNUSABLE -> Removing(busy = true)
            }
        }
    }

    /**
     * Ответ о месте: чьё место прочитано и что прочитано. Пустая аптечка у **своей** коробки —
     * «полки не стало», у чужой — «ещё не читали», и путать их нельзя.
     */
    private class Place(private val medKitId: Uuid? = null, private val medKit: MedKitProjection? = null) {

        fun isOf(pack: PackageProjection?) = medKitId == pack?.medKit?.id

        fun of(pack: PackageProjection?) = medKit.takeIf { isOf(pack) }
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
    /** Коробка перечитывается у сервера: карточка ждёт (PLAN E4). */
    val isFreshening: Boolean = false,
    /** Место коробки ещё не прочитано: «аптечка неизвестна» — утверждение, и до чтения оно ложно. */
    val isPlaceUnread: Boolean = false,
    val pack: PackagePresentationDTO? = null,
    val medKitName: String? = null,
    val holdingCourseTitle: String? = null,
    /** Когда из коробки брали последний раз — днём в зоне человека, как он это помнит. */
    val lastUsedOn: LocalDate? = null,
    /** Сервер отверг изменение этой коробки: расхождение о числе, и лечится оно пересчётом. */
    val isRefusedByServer: Boolean = false,
    val today: LocalDate = LocalDate.MIN,
    val isGone: Boolean = false,
    val asksToRemove: Boolean = false,
    val isBusy: Boolean = false,
    val isRemoved: Boolean = false
) {
    /** Пока первое чтение не пришло, карточка не говорит ни «есть», ни «нет». */
    val isLoading: Boolean get() = isFreshening || isPlaceUnread || (pack == null && !isGone)
}
