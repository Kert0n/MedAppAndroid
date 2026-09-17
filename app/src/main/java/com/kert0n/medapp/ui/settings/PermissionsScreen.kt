package com.kert0n.medapp.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.settings.PermissionState
import com.kert0n.medapp.presentation.settings.PermissionsPresentationDTO

/**
 * Разрешения (PLAN H3 №27): три строки — уведомления, точные будильники, камера — и у каждой
 * состояние словом, значком и цветом. Выключенное нажимается и ведёт туда, где это чинится;
 * то, чего не починить (камеры нет, точности до Android 12 не существует), не нажимается — за
 * нажатием ничего бы не стояло.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    state: PermissionsPresentationDTO,
    onFixNotifications: () -> Unit,
    onFixAlarms: () -> Unit,
    onFixCamera: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            PermissionRow(
                icon = R.drawable.ic_notifications,
                name = stringResource(R.string.permissions_notifications),
                state = state.notifications,
                words = when (state.notifications) {
                    PermissionState.MUTED -> stringResource(R.string.permissions_notifications_muted)
                    else -> null
                },
                onFix = onFixNotifications
            )
            PermissionRow(
                icon = R.drawable.ic_alarm,
                name = stringResource(R.string.permissions_exact_alarms),
                state = state.exactAlarms,
                words = when (state.exactAlarms) {
                    PermissionState.DENIED -> stringResource(R.string.permissions_exact_alarms_denied)
                    else -> null
                },
                onFix = onFixAlarms
            )
            PermissionRow(
                icon = R.drawable.ic_photo_camera,
                name = stringResource(R.string.permissions_camera),
                state = state.camera,
                words = null,
                onFix = onFixCamera
            )
        }
    }
}

/**
 * Строка разрешения. [words] — своё объяснение состояния, когда общего мало; иначе состояние
 * называется общими словами. Цвет беды — всегда со значком и словом: одним цветом нельзя.
 */
@Composable
private fun PermissionRow(
    @DrawableRes icon: Int,
    name: String,
    state: PermissionState,
    words: String?,
    onFix: () -> Unit
) {
    val color = if (state.isTrouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(name, color = color) },
        supportingContent = { Text(words ?: state.words()) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = color) },
        trailingContent = {
            Icon(
                painterResource(state.icon()),
                contentDescription = state.words(),
                tint = if (state.isTrouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().let { if (state.isFixable) it.clickable(onClick = onFix) else it }
    )
}

@Composable
private fun PermissionState.words(): String = stringResource(
    when (this) {
        PermissionState.GRANTED -> R.string.permissions_granted
        PermissionState.DENIED -> R.string.permissions_denied
        PermissionState.MUTED -> R.string.permissions_denied
        PermissionState.ABSENT -> R.string.permissions_absent
        PermissionState.NOT_NEEDED -> R.string.permissions_not_needed
    }
)

@DrawableRes
private fun PermissionState.icon(): Int = when (this) {
    PermissionState.GRANTED -> R.drawable.ic_check_circle
    PermissionState.DENIED, PermissionState.MUTED -> R.drawable.ic_warning
    PermissionState.ABSENT, PermissionState.NOT_NEEDED -> R.drawable.ic_block
}
