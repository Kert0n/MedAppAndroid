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
import com.kert0n.medapp.presentation.scan.ScannerUiState
import com.kert0n.medapp.ui.EmptyState

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
                // Единственное, что сканер говорит от себя: код перед камерой не тот. Всё, что он
                // узнал, он показывает не здесь, а на том экране, куда ведёт (C1).
                if (state.isUnsupported) {
                    Unsupported(Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

/**
 * Чужой код назван словами: молчание неотличимо от сломанной камеры, а человек будет держать
 * коробку перед телефоном и ждать (PLAN H3 «Набор сканера»).
 */
@Composable
private fun Unsupported(modifier: Modifier) = ElevatedCard(modifier) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            stringResource(R.string.scanner_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            // Ширину забирает текст, а не значок: длинная фраза иначе переносится по слогам.
            modifier = Modifier.weight(1f)
        )
    }
}
