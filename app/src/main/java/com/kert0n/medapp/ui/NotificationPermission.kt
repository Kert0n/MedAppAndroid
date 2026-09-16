package com.kert0n.medapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Просьба о разрешении на уведомления — в тот миг, когда она объяснима.
 *
 * Спрашивают **при начале лечения** (PLAN H3 «Уведомления на экране»): это первое, что заводит
 * напоминания, и польза видна человеку сразу. Просить при первом запуске — просить до того, как
 * есть о чём напоминать: человек отказывает не задумываясь, а второго раза Android не даёт.
 *
 * Отказ ничего не ломает: возвращаемая просьба ничего не ждёт и ни на что не влияет — лечение
 * начато, попап срока работает, а о немоте скажет страница дня.
 *
 * До Android 13 разрешения не существует, и просьба молчит: спрашивать систему о том, чего у неё
 * нет, — придумывать себе состояние.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, ask) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            // Уже дали — спрашивать нечего; отказали раньше — система сама покажет только системный
            // отказ, и это её дело, а не наше.
            if (!granted) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
