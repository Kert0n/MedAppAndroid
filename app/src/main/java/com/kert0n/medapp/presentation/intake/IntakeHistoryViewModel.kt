package com.kert0n.medapp.presentation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.feature.intake.IntakeReadings
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.course.CourseStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * История приёмов (PLAN H3 №19). Входов два, и каждый спрашивает своё: с карточки коробки — что из
 * неё принимали, с карточки лечения — как оно шло.
 *
 * Читается она **по записям**, а не по вещам: коробка кончилась и выброшена, а приёмы из неё
 * остались — им нечего терять, потому что имя и единица лежат в самом факте (PLAN D3, D6). Оттого
 * экран не спрашивает, есть ли ещё коробка: он спрашивает, что из неё было.
 */
@HiltViewModel(assistedFactory = IntakeHistoryViewModel.Factory::class)
class IntakeHistoryViewModel @AssistedInject constructor(
    today: Today,
    courses: CourseStorageRepository,
    intakes: IntakeReadings,
    packages: PackageReadings,
    @Assisted("courseId") private val courseId: Uuid?,
    @Assisted("packageId") private val packageId: Uuid?
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("courseId") courseId: Uuid?,
            @Assisted("packageId") packageId: Uuid?
        ): IntakeHistoryViewModel
    }

    init {
        require((courseId == null) != (packageId == null)) {
            "история спрашивается об одном: или о лечении, или о коробке"
        }
    }

    /**
     * Чьи приёмы читаем. Заголовок называет то, о чём человек спросил, и **переживает** это:
     * кончившаяся коробка из живых пропадает, а её приёмы остаются — имя тогда берётся у них же,
     * из ссылки внутри факта (PLAN D3, D6). Живую коробку спрашиваем на случай пустой истории: у
     * неё ещё нет строк, а имя у экрана уже должно быть.
     */
    private val subject = when {
        courseId != null -> courses.observeRecord(courseId).map { it?.title.orEmpty() }
        else -> packages.observe(requireNotNull(packageId)).map { it?.facts?.name }
    }

    private val read = when {
        courseId != null -> intakes.observeOfCourse(courseId)
        else -> intakes.observeOfPackage(requireNotNull(packageId))
    }

    val state: StateFlow<IntakeHistoryUiState> = combine(
        read,
        subject,
        // Лечения нужны той истории, что пришла с коробки: строка называет, по какому лечению
        // принимали, а имя лечения живёт в записи эпизода, а не в приёме.
        courses.observeRecords(),
        today.observe()
    ) { intakes, subject, records, day ->
        val titles = records.associate { it.id to it.title }
        IntakeHistoryUiState(
            title = subject ?: intakes.firstNotNullOfOrNull { it.taken?.pkg?.name }.orEmpty(),
            // Сверху — последнее: человек открывает историю, чтобы узнать, что было недавно.
            rows = intakes.sortedByDescending { it.happenedAt }.map { it.row(titles, day.zone) }
        )
    }.stateInScreen(viewModelScope, reading, IntakeHistoryUiState(isLoading = true))

    /** Когда это было: у состоявшегося — момент приёма, у неотвеченного — его место в расписании. */
    private val IntakeProjection.happenedAt: Instant
        get() = when (this) {
            is IntakeProjection.Scheduled -> taken?.at ?: slot.at
            is IntakeProjection.Unplanned -> dose.at
        }

    private fun IntakeProjection.row(
        titles: Map<Uuid, String>,
        zone: ZoneId
    ): IntakeHistoryRowPresentationDTO {
        val at = happenedAt.atZone(zone)
        return IntakeHistoryRowPresentationDTO(
            id = id,
            on = at.toLocalDate(),
            at = at.toLocalTime(),
            amount = (taken?.amount ?: (this as? IntakeProjection.Scheduled)?.plannedAmount)
                ?.quantity?.toPresentationDTO(),
            subject = subjectOf(titles),
            state = when (this) {
                is IntakeProjection.Unplanned -> IntakeHistoryRowPresentationDTO.State.ONE_OFF
                is IntakeProjection.Scheduled -> when (status) {
                    IntakeStatus.TAKEN -> IntakeHistoryRowPresentationDTO.State.TAKEN
                    IntakeStatus.MISSED -> IntakeHistoryRowPresentationDTO.State.MISSED
                    IntakeStatus.CANCELLED -> IntakeHistoryRowPresentationDTO.State.CANCELLED
                    IntakeStatus.PLANNED -> IntakeHistoryRowPresentationDTO.State.PLANNED
                }
            }
        )
    }

    /**
     * Что называет строка: пришли с коробки — лечение, по которому принимали; пришли с лечения —
     * коробку, из которой взяли. Того, ради чего экран открыли, в строке нет: оно в заголовке.
     */
    private fun IntakeProjection.subjectOf(titles: Map<Uuid, String>): String? = when {
        packageId != null -> (this as? IntakeProjection.Scheduled)?.let { titles[it.courseId] }
        else -> taken?.pkg?.name ?: (this as? IntakeProjection.Scheduled)?.plannedPackage?.name
    }
}
