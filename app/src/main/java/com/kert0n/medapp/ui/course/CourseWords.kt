package com.kert0n.medapp.ui.course

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.course.SchedulePresentationDTO
import com.kert0n.medapp.ui.words
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Назначение одной строкой: «2 таблетка · 4 раза в день · пн–пт». Чего не записано, того в
 * строке нет; не записано ничего — сказано словами, а не пустой строкой.
 */
@Composable
internal fun CoursePresentationDTO.prescriptionWords(): String {
    val parts = listOfNotNull(
        dose?.words(),
        schedule?.let { pluralStringResource(R.plurals.course_times_a_day, it.times.size, it.times.size) },
        schedule?.days?.words()
    )
    return if (parts.isEmpty()) stringResource(R.string.course_prescription_missing) else parts.joinToString(" · ")
}

/**
 * Дни недели словами: все семь — «ежедневно», подряд — «пн–пт», вразнобой — «пн, ср, пт».
 * Короткие имена берутся у локали, а не хранятся строками: неделя у неё уже есть.
 */
@Composable
internal fun Set<DayOfWeek>.words(): String {
    if (size == DayOfWeek.entries.size) return stringResource(R.string.course_days_daily)
    val ordered = sortedBy { it.value }
    val contiguous = ordered.size > 2 && ordered.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
    return if (contiguous) stringResource(R.string.course_days_range, ordered.first().short(), ordered.last().short())
    else ordered.joinToString(", ") { it.short() }
}

internal fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

internal fun SchedulePresentationDTO.timesWords(): String = times.joinToString(", ") { it.toString() }
