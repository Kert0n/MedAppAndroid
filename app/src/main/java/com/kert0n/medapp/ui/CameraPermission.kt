package com.kert0n.medapp.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Просьба о разрешении на камеру — в тот миг, когда она объяснима: человек открыл сканер, и
 * камера нужна ему прямо сейчас (PLAN H3 «Набор сканера»).
 *
 * Ответ системы приходит в [onAnswered] **любой**: и согласие, и отказ меняют то, что человек
 * видит, — отказ ведёт к ручному вводу, а не в тупик. Уже разрешённая камера отвечает сразу и без
 * диалога, поэтому просьба годится и как «открыть камеру, если можно».
 *
 * Камера, в отличие от уведомлений, существует на всех поддерживаемых версиях, поэтому молчаливой
 * ветки «такого разрешения нет» здесь не бывает.
 */
@Composable
fun rememberCameraPermissionRequest(onAnswered: (Boolean) -> Unit): () -> Unit {
    val answered = rememberUpdatedState(onAnswered)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answered.value(granted)
    }
    return remember(ask) { { ask.launch(Manifest.permission.CAMERA) } }
}
