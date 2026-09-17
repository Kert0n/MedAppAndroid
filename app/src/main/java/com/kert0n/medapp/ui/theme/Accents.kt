package com.kert0n.medapp.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Цвета, которых в схеме Material нет, а в продукте они значат своё (PLAN H3 «Дизайн»).
 *
 * Схема даёт красный для беды и `tertiary` для спокойного уточнения, но «решение ещё в пути» —
 * не беда и не уточнение: это **ожидание**, и глазу оно должно читаться янтарём, как у любого
 * светофора (решение владельца 2026-09-17). Роли для него в Material нет, поэтому она заводится
 * здесь — целиком для обеих тем, а не выводится из умолчаний.
 *
 * Цвет при этом по-прежнему не единственный носитель: рядом со всяким янтарным стоят значок и
 * слова.
 */
data class MedAppAccents(
    /** Слова и значок ожидания. */
    val pending: Color,
    /** Подложка ожидания: ею гасится строка, о которой идёт разговор с сервером. */
    val pendingContainer: Color,
    /** Слова на этой подложке. */
    val onPendingContainer: Color
)

internal val LightAccents = MedAppAccents(
    pending = Color(0xFF8A5A00),
    pendingContainer = Color(0xFFFFDEA6),
    onPendingContainer = Color(0xFF2B1700)
)

internal val DarkAccents = MedAppAccents(
    pending = Color(0xFFFFB951),
    pendingContainer = Color(0xFF5C3D00),
    onPendingContainer = Color(0xFFFFDEA6)
)

/**
 * Провайдера ставит `MedAppTheme`. Умолчания нет намеренно: незаданная роль приходит не пустой, а
 * чужой, и однажды этим уже покрасилась панель навигации (PLAN H3 «Дизайн»).
 */
val LocalAccents = staticCompositionLocalOf<MedAppAccents> {
    error("акценты ставит MedAppTheme: без него цвет ожидания взять неоткуда")
}
