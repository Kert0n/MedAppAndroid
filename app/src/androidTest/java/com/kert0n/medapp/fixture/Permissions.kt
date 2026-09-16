package com.kert0n.medapp.fixture

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Разрешение на уведомления для проверок, которые проходят **через начало лечения**.
 *
 * Приложение спрашивает его в этот миг (PLAN H3 «Уведомления на экране»), и системный диалог
 * закрывает собой всё окно: проверка теряет дерево Compose и падает не о том, о чём написана.
 * Выдача разрешения заранее ставит проверку в положение человека, который согласился, — а то, что
 * бывает при отказе, проверяется своими проверками, где отказ и есть предмет.
 *
 * До Android 13 разрешения не существует, и выдавать нечего.
 */
fun allowNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        Manifest.permission.POST_NOTIFICATIONS
    )
}

/**
 * Разрешения, о которых проверка не спрашивает: всё позволено. Отказ — предмет своих проверок, и
 * там он называется явно.
 */
object AllAllowed : com.kert0n.medapp.platform.settings.DevicePermissions, com.kert0n.medapp.domain.notification.NotificationReadiness {

    override fun now() = com.kert0n.medapp.domain.notification.Readiness(allowed = true)

    override fun current() = com.kert0n.medapp.platform.settings.PermissionStates(
        notifications = true,
        exactAlarms = true,
        camera = com.kert0n.medapp.platform.settings.CameraAccess.GRANTED
    )
}
