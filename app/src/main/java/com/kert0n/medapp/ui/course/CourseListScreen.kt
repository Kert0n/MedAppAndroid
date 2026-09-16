package com.kert0n.medapp.ui.course

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.course.CourseListPresentationDTO
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.course.ShortagePresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState

/**
 * Список курсов (PLAN H3 №13) — содержимое места «План» в режиме «Курсы»; шапку и кнопку
 * держит место. Идущие первыми: у них есть, что проверить сегодня. Нехватка названа словами и
 * значком, а не одним цветом.
 */
@Composable
fun CourseListContent(
    state: ScreenState<CourseListPresentationDTO>,
    onOpen: (CoursePresentationDTO) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val courses = (state as? ScreenState.Ready)?.value
    when {
        // Первое чтение базы ещё не пришло: говорить «пусто» рано — это была бы неправда.
        courses == null -> LoadingState(modifier)
        courses.isEmpty -> EmptyState(
            text = stringResource(R.string.courses_empty) + "\n" + stringResource(R.string.courses_empty_hint),
            modifier = modifier,
            actionText = stringResource(R.string.courses_add),
            onAction = onAdd
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
        ) {
            shelf(R.string.courses_running, courses.running, onOpen)
            shelf(R.string.courses_drafts, courses.drafts, onOpen)
            shelf(R.string.courses_finished, courses.finished, onOpen)
        }
    }
}

/** Полка списка: заголовок и строки; пустая полка не показывается вовсе — заголовок без строк ничего не говорит. */
private fun LazyListScope.shelf(title: Int, courses: List<CoursePresentationDTO>, onOpen: (CoursePresentationDTO) -> Unit) {
    if (courses.isEmpty()) return
    item(key = "shelf-$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    items(courses, key = { it.id }) { course -> CourseRow(course, onOpen = { onOpen(course) }) }
}

@Composable
private fun CourseRow(course: CoursePresentationDTO, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(course.title) },
        supportingContent = {
            when (course.kind) {
                CoursePresentationDTO.Kind.DRAFT -> Text(course.note ?: course.prescriptionWords())
                CoursePresentationDTO.Kind.RUNNING -> Column {
                    Text(course.prescriptionWords())
                    course.shortage?.let { Shortage(it) }
                }
                CoursePresentationDTO.Kind.COMPLETED ->
                    Text(stringResource(R.string.course_completed_on, course.closedOn?.format(DAY).orEmpty()))
                CoursePresentationDTO.Kind.CANCELLED ->
                    Text(stringResource(R.string.course_cancelled_on, course.closedOn?.format(DAY).orEmpty()))
            }
        },
        leadingContent = {
            val draft = course.kind == CoursePresentationDTO.Kind.DRAFT
            Icon(
                painterResource(if (draft) R.drawable.ic_edit else R.drawable.ic_medication),
                contentDescription = if (draft) stringResource(R.string.course_draft) else null
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    )
}

/** Нехватка — словами и значком: «не хватает 19 приёмов с 11.09.2026». */
@Composable
internal fun Shortage(shortage: ShortagePresentationDTO, modifier: Modifier = Modifier) {
    val missing = pluralStringResource(R.plurals.course_shortage, shortage.missingDoses, shortage.missingDoses)
    val text = shortage.firstUncoveredOn?.let { stringResource(R.string.course_shortage_from, missing, it.format(DAY)) }
        ?: missing
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
