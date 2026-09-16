package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Лечение глазами списка и шапки карточки (PLAN H3 №13, №14), с равенством по содержимому.
 *
 * Три вида одной вещи различает [kind]: черновик, идущее и законченное лечение. У черновика
 * назначение собирается по частям, поэтому его поля необязательны; у начатого они есть по
 * типу, и пустыми здесь не бывают. [shortage] — нехватка идущего курса, названная числом и
 * днём: значок без слов сообщением не является (H3). [closedOn] есть только у законченного.
 */
data class CoursePresentationDTO(
    val id: Uuid,
    val title: String,
    val note: String?,
    val kind: Kind,
    val dose: QuantityPresentationDTO?,
    val form: FormPresentationDTO?,
    val schedule: SchedulePresentationDTO?,
    val totalDoses: Int?,
    val shortage: ShortagePresentationDTO?,
    val closedOn: LocalDate?
) {
    enum class Kind { DRAFT, RUNNING, COMPLETED, CANCELLED }
}

/** Календарь назначения словами экрана: с какого дня, по каким дням, во сколько; зона — курса (D5). */
data class SchedulePresentationDTO(
    val start: LocalDate,
    val days: Set<DayOfWeek>,
    val times: List<LocalTime>,
    val zone: ZoneId
)

/** Чего не хватает: сколько приёмов и с какого дня — дата в зоне курса. */
data class ShortagePresentationDTO(val missingDoses: Int, val firstUncoveredOn: LocalDate?)

/**
 * Список курсов — три полки одного списка (H3 №13): идущие первыми, потом черновики, потом
 * законченные. Деление — здесь, а не на экране: какой курс куда, решает его вид, а не вёрстка.
 */
data class CourseListPresentationDTO(
    val running: List<CoursePresentationDTO>,
    val drafts: List<CoursePresentationDTO>,
    val finished: List<CoursePresentationDTO>
) {
    val isEmpty: Boolean get() = running.isEmpty() && drafts.isEmpty() && finished.isEmpty()
}
