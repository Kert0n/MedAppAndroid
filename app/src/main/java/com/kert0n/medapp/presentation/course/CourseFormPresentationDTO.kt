package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Форма лечения в том виде, в каком её держит экран: строками, как человек напечатал
 * (PLAN H3 №15). Обязательно здесь одно — название: «записал у врача, куплю завтра» —
 * законный черновик с одной заметкой (D5); остальное дописывается по частям.
 *
 * Дата, дни и времена — уже значения, а не строки: их называет календарь и часы, и состояния
 * «13.20» у них не бывает. [zone] — зона курса (D5): у записанного расписания — его, у нового —
 * та, в которой человек сейчас.
 */
data class CourseFormPresentationDTO(
    val title: String = "",
    val note: String = "",
    val doseAmount: String = "",
    val unit: UnitPresentationDTO? = null,
    val form: FormPresentationDTO? = null,
    val start: LocalDate? = null,
    val days: Set<DayOfWeek> = emptySet(),
    val times: List<LocalTime> = emptyList(),
    val totalDoses: String = "",
    val zone: ZoneId = ZoneId.systemDefault()
)
