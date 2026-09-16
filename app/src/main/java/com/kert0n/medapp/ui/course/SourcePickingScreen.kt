package com.kert0n.medapp.ui.course

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.presentation.course.Attachability
import com.kert0n.medapp.presentation.course.PackageAttachmentPresentationDTO
import com.kert0n.medapp.presentation.course.SourcePickingUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import kotlin.uuid.Uuid

/**
 * Выбор источника (PLAN H3 №17): коробки, которые можно подключить к этому лечению. Подключённая
 * приходит в стек с нулём приёмов — сколько из неё брать, человек решает там, где виден весь стек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickingScreen(
    state: SourcePickingUiState,
    onAttach: (Uuid) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.course_picking)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.isGone -> ErrorMessage(
                text = stringResource(R.string.course_missing),
                modifier = Modifier.padding(padding)
            )
            state.packages.isEmpty() -> EmptyState(
                text = stringResource(R.string.course_picking_empty),
                modifier = Modifier.padding(padding)
            )
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                state.message?.let {
                    Text(
                        it.words(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.packages, key = { it.packageId }) { pack ->
                        PackageRow(pack, onAttach = { onAttach(pack.packageId) }, enabled = !state.isAttaching)
                    }
                }
            }
        }
    }
}

/**
 * Строка коробки: чем она названа, где лежит и сколько в ней свободно. Неподходящая не прячется,
 * а выключается и второй строкой говорит **почему** — иначе человек ищет пропавшую коробку
 * глазами и не находит (PLAN H3 №17).
 */
@Composable
private fun PackageRow(pack: PackageAttachmentPresentationDTO, onAttach: () -> Unit, enabled: Boolean) {
    val takeable = pack.isAttachable
    ListItem(
        headlineContent = { Text(pack.name) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        pack.medKitName,
                        pack.availableToMe?.let { stringResource(R.string.course_source_free, it.amount, it.unit.name) }
                    ).joinToString(" · ")
                )
                if (!takeable) {
                    Text(pack.attachability.words(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (takeable) 1f else 0.6f)
            .clickable(enabled = enabled && takeable, onClick = onAttach)
    )
}

/** Почему коробку нельзя взять — словами, которые ведут к действию. */
@Composable
private fun Attachability.words(): String = when (this) {
    Attachability.Attachable -> ""
    Attachability.Attached -> stringResource(R.string.course_attach_already)
    is Attachability.HeldByCourse ->
        title?.let { stringResource(R.string.course_attach_held_named, it) }
            ?: stringResource(R.string.course_attach_held)
    Attachability.NeedsForm -> stringResource(R.string.course_attach_needs_form)
    is Attachability.Mismatch -> stringResource(
        when (fault) {
            CourseSource.Fault.FORM_MISMATCH -> R.string.course_attach_other_form
            CourseSource.Fault.UNIT_MISMATCH -> R.string.course_attach_other_unit
        }
    )
    Attachability.Unusable -> stringResource(R.string.course_attach_unusable)
    Attachability.PrescriptionIncomplete -> stringResource(R.string.course_attach_incomplete)
}
