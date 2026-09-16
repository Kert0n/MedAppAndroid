package com.kert0n.medapp.platform.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.ReminderAlarms
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Разрешения — у системы. Точность будильников спрашивается у того, кто ими владеет
 * ([ReminderAlarms.canBeExact]), уведомления — у владельца каналов ([NotificationReadiness]):
 * второго ответа на тот же вопрос здесь не заводится.
 */
class AndroidDevicePermissions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: ReminderAlarms,
    private val readiness: NotificationReadiness
) : DevicePermissions {

    override fun current(): PermissionStates = PermissionStates(
        // Тот же ответ, что у показа: второго опроса системы здесь не заводится (PLAN C1).
        notifications = readiness.now().allowed,
        exactAlarms = alarms.canBeExact,
        camera = when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> CameraAccess.ABSENT
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> CameraAccess.GRANTED
            else -> CameraAccess.DENIED
        }
    )
}
