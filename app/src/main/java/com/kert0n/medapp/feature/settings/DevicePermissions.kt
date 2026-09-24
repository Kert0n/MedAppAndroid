package com.kert0n.medapp.feature.settings

/**
 * Что система разрешила приложению (PLAN H3 №27). Не наши данные, а её состояние: читается, а
 * меняет его человек в системных настройках. Просить разрешения — дело экрана (U5, U11).
 */
interface DevicePermissions {

    fun current(): PermissionStates
}

/**
 * Три разрешения, о которых экран говорит по-разному. У камеры случаев три, потому что поведение
 * различает три: без камеры вовсе сканера нет и просить нечего — остаётся ручной ввод (T-45).
 */
data class PermissionStates(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val camera: CameraAccess
)

enum class CameraAccess { GRANTED, DENIED, ABSENT }
