package com.kert0n.medapp.presentation.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.platform.settings.CameraAccess
import com.kert0n.medapp.platform.settings.DevicePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Разрешения (PLAN H3 №27): состояние **системы**, не наше. Читается при создании и заново при
 * каждом возвращении на экран ([refresh]) — меняет его человек в системных настройках, и своего
 * мнения о нём приложение не держит. Можно ли сказать, отвечает тот же [NotificationReadiness],
 * что и показ: заглушённый канал иначе выглядел бы разрешённым (PLAN C1).
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissions: DevicePermissions,
    private val readiness: NotificationReadiness
) : ViewModel() {

    private val _state = MutableStateFlow(now())

    val state: StateFlow<PermissionsPresentationDTO> = _state.asStateFlow()

    /** Человек вернулся из системных настроек: спрашиваем заново — там он мог всё и починить. */
    fun refresh() {
        _state.value = now()
    }

    private fun now(): PermissionsPresentationDTO {
        val readiness = readiness.now()
        val states = permissions.current()
        return PermissionsPresentationDTO(
            notifications = when {
                !readiness.allowed -> PermissionState.DENIED
                NotificationChannel.INTAKES in readiness.muted -> PermissionState.MUTED
                else -> PermissionState.GRANTED
            },
            // До Android 12 точность будильников не спрашивают: разрешения не существует.
            exactAlarms = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> PermissionState.NOT_NEEDED
                states.exactAlarms -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            },
            camera = when (states.camera) {
                CameraAccess.GRANTED -> PermissionState.GRANTED
                CameraAccess.DENIED -> PermissionState.DENIED
                CameraAccess.ABSENT -> PermissionState.ABSENT
            }
        )
    }
}
