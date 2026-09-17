package com.kert0n.medapp.presentation.settings

/**
 * Что система разрешила приложению — словами экрана (PLAN H3 №27, «Разрешения»). Три строки, у
 * каждой своё состояние; беда — то, что человек может починить: выключенное и заглушённое.
 */
data class PermissionsPresentationDTO(
    val notifications: PermissionState,
    val exactAlarms: PermissionState,
    val camera: PermissionState
) {
    /** Есть что чинить: об этом говорит и строка в «Опциях», не дожидаясь, пока сюда зайдут. */
    val hasTrouble: Boolean get() = listOf(notifications, exactAlarms, camera).any { it.isTrouble }
}

/**
 * Состояние одного разрешения. Случаев пять, потому что экран делает с ними разное: два
 * чинятся нажатием ([DENIED], [MUTED]), два не чинятся вовсе — камеры нет ([ABSENT]), точности
 * не существует до Android 12 ([NOT_NEEDED]), — и одно просто хорошо ([GRANTED]).
 */
enum class PermissionState {
    GRANTED,
    DENIED,
    /** Разрешено приложению, но канал «Приёмы» человек заглушил сам — чинится там же, другими словами. */
    MUTED,
    ABSENT,
    NOT_NEEDED;

    val isTrouble: Boolean get() = this == DENIED || this == MUTED

    /** Есть куда вести: строка нажимается только тогда, когда за нажатием что-то стоит. */
    val isFixable: Boolean get() = isTrouble
}
