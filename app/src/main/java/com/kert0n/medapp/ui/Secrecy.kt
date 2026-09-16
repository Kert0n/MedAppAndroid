package com.kert0n.medapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Пока это на экране, снимок экрана и показ в списке недавних запрещены (`FLAG_SECURE`, PLAN G3).
 * Ставится там, где виден **секрет** — ключ приглашения, — и снимается вместе с ним: держать запрет
 * на всё приложение значило бы отнять у человека снимки экрана ради одного места.
 *
 * Окно берётся у того, кто рисует: у диалога оно своё, и запрет, поставленный окну активности,
 * его содержимое не закрывает.
 */
@Composable
fun SecretOnScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
