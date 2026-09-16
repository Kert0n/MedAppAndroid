package com.kert0n.medapp.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.presentation.scan.ScannerCamera
import com.kert0n.medapp.presentation.scan.ScannerNotice
import com.kert0n.medapp.presentation.scan.ScannerUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.NavigationRow

/**
 * Сканер (PLAN H3 №24). **Первым — живая картинка камеры**: человек уже держит коробку перед
 * телефоном, и объяснение с кнопкой «открыть камеру» стоило бы ему лишнего нажатия на самом частом
 * пути (C1 «Сканер открывается камерой»).
 *
 * Ручной ввод стоит внизу всегда, а не только при беде: код бывает затёрт, а коробка — без кода
 * вовсе, и уходить с места за этим незачем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onCode: (ScannedCode) -> Unit,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onJoin: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_scanner)) }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.camera) {
                    ScannerCamera.READY -> CodeViewfinder(onCode)
                    ScannerCamera.UNASKED -> EmptyState(
                        text = stringResource(R.string.scanner_camera_denied),
                        icon = R.drawable.ic_photo_camera,
                        actionText = stringResource(R.string.scanner_camera_allow),
                        onAction = onAllow
                    )
                    // Спрошено и всё равно нельзя — диалога больше не будет: второй отказ Android
                    // запоминает, и остаётся его собственный экран приложения.
                    ScannerCamera.REFUSED -> EmptyState(
                        text = stringResource(R.string.scanner_camera_refused),
                        icon = R.drawable.ic_photo_camera,
                        actionText = stringResource(R.string.scanner_camera_settings),
                        onAction = onOpenSettings
                    )
                    ScannerCamera.ABSENT -> EmptyState(
                        text = stringResource(R.string.scanner_camera_absent),
                        icon = R.drawable.ic_no_photography
                    )
                }
                state.notice?.let {
                    Notice(
                        notice = it,
                        onJoin = onJoin,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    )
                }
            }
            HorizontalDivider()
            NavigationRow(
                icon = R.drawable.ic_keyboard,
                text = stringResource(R.string.scanner_manual),
                onClick = onManual,
                supporting = stringResource(R.string.scanner_manual_explained)
            )
        }
    }
}

/**
 * Что сказано о прочитанном коде. Приглашение сканер не принимает сам: вступление живёт на своём
 * экране, где у него есть и поле кода, и все исходы (C1 «Приглашение в сканере не вступает»).
 */
@Composable
private fun Notice(notice: ScannerNotice, onJoin: () -> Unit, modifier: Modifier) = ElevatedCard(modifier) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(
                    if (notice == ScannerNotice.INVITATION) R.drawable.ic_qr_code_2 else R.drawable.ic_warning
                ),
                contentDescription = null,
                tint = if (notice == ScannerNotice.INVITATION) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Text(
                stringResource(
                    if (notice == ScannerNotice.INVITATION) {
                        R.string.scanner_invitation
                    } else {
                        R.string.scanner_unsupported
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                // Ширину забирает текст, а не значок: длинная фраза иначе переносится по слогам.
                modifier = Modifier.weight(1f)
            )
        }
        if (notice == ScannerNotice.INVITATION) {
            NavigationRow(
                icon = R.drawable.ic_group_add,
                text = stringResource(R.string.med_kit_joining_join),
                onClick = onJoin
            )
        }
    }
}
