package com.kert0n.medapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Тема приложения: своя зелёная палитра при любом свете и на любом Android.
 *
 * Динамического цвета здесь нет **как возможности**, а не как выключенного умолчания: на
 * Android 12+ он перекрасил бы приложение обоями, и фирменной палитры не осталось бы видно вовсе
 * (PLAN H3). Параметр, который всегда передают одним и тем же значением, — это не выбор.
 *
 * [darkTheme] по умолчанию спрашивает систему; аргументом он остаётся ради снимков и проверок,
 * где обе схемы нужно показать рядом.
 *
 * Рядом со схемой Material едут [MedAppAccents] — цвета продукта, которым роли в Material нет.
 * Выбирает между светлым и тёмным янтарём тема, а не каждый экран по-своему.
 */
@Composable
fun MedAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalMedAppAccents provides if (darkTheme) DarkAccents else LightAccents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content
        )
    }
}
