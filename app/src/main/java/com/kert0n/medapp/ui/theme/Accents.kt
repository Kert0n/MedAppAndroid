package com.kert0n.medapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Цвета продукта, которым нет роли в Material. Янтарный значит «занято или не хватает» — это не
 * беда и не отказ, и `error` тут сказал бы неправду: красным в приложении говорится только о
 * просрочке (PLAN H3).
 *
 * Величина, а не набор констант: тёмная схема берёт другие тона, и выбор между ними делает тема,
 * а не каждый экран по-своему.
 */
@Immutable
data class MedAppAccents(val reserved: Color, val reservedContainer: Color)

/** Янтарный светлой схемы: тёмный тон на светлой подложке, читаемый как текст. */
val LightAccents = MedAppAccents(
    reserved = Color(0xFF8A5300),
    reservedContainer = Color(0xFFFFDDB3)
)

val DarkAccents = MedAppAccents(
    reserved = Color(0xFFFFB95C),
    reservedContainer = Color(0xFF673F00)
)

val LocalMedAppAccents = staticCompositionLocalOf { LightAccents }

/** Читается как роль схемы: `MaterialTheme.accents.reserved` рядом с `colorScheme.error`. */
val MaterialTheme.accents: MedAppAccents
    @Composable
    @ReadOnlyComposable
    get() = LocalMedAppAccents.current
