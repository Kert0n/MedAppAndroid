package com.kert0n.medapp.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.course.CourseListPresentationDTO
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPermissionsPresentationDTO
import kotlin.uuid.Uuid
import com.kert0n.medapp.ui.course.CourseListContent

/** Два состояния места «План»: страница дня и список курсов (PLAN H3 «Набор курсов»). */
enum class PlanMode { DAY, COURSES }

/**
 * Место «План». Расписание дня и список курсов — две стороны одного «что и когда я принимаю»,
 * поэтому это два состояния одного места, а не два места. Режим держит оболочка: он переживает
 * уход в другое место и возвращение, как и всё состояние стопки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    mode: PlanMode,
    onMode: (PlanMode) -> Unit,
    courses: ScreenState<CourseListPresentationDTO>,
    dayPage: @Composable (daysAhead: Int) -> ScreenState<DayPagePresentationDTO>,
    dayPermissions: DayPermissionsPresentationDTO,
    onOpenIntake: (DayItemPresentationDTO) -> Unit,
    onConfirmIntake: (Uuid) -> Unit,
    onDeclineIntake: (Uuid) -> Unit,
    onAcknowledgeIntake: (Uuid) -> Unit,
    onDismissDayMessage: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixAlarms: () -> Unit,
    onOpenCourse: (CoursePresentationDTO) -> Unit,
    onAddCourse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listed = (courses as? ScreenState.Ready)?.value
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.plan_title)) }) },
        floatingActionButton = {
            // У пустого списка кнопка одна — та, что в самом рассказе.
            if (mode == PlanMode.COURSES && listed != null && !listed.isEmpty) {
                FloatingActionButton(onClick = onAddCourse) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.courses_add))
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                PlanMode.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = mode == entry,
                        onClick = { onMode(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = PlanMode.entries.size),
                        label = { Text(stringResource(entry.label)) }
                    )
                }
            }
            when (mode) {
                PlanMode.DAY -> DayPages(
                    page = dayPage,
                    permissions = dayPermissions,
                    onOpen = onOpenIntake,
                    onConfirm = onConfirmIntake,
                    onDecline = onDeclineIntake,
                    onAcknowledge = onAcknowledgeIntake,
                    onDismissMessage = onDismissDayMessage,
                    onFixNotifications = onFixNotifications,
                    onFixAlarms = onFixAlarms,
                    modifier = Modifier.fillMaxSize()
                )
                PlanMode.COURSES -> CourseListContent(
                    state = courses,
                    onOpen = onOpenCourse,
                    onAdd = onAddCourse,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private val PlanMode.label: Int
    get() = when (this) {
        PlanMode.DAY -> R.string.plan_mode_day
        PlanMode.COURSES -> R.string.plan_mode_courses
    }
