package com.kert0n.medapp.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * Как экраны сменяют друг друга. Движение в приложении одно, и оно отвечает на один вопрос:
 * **человек углубился или перешёл вбок**.
 *
 * - **Вглубь** — экран приезжает справа и уезжает вправо, а прежний отступает влево. Возврат
 *   играет то же движение задом наперёд, поэтому жест назад и кнопка назад выглядят одинаково.
 * - **Вбок** — пять мест нижней навигации друг другу не глубина, а соседи: они сменяются
 *   проявлением, без сдвига. Сдвиг между соседями сказал бы неправду о том, где человек был.
 *
 * Заданы **все четыре** перехода, и это не формальность: незаданные библиотека подменяет своими,
 * а предиктивный жест назад — системным «сжатием со всех сторон». Оттого одно и то же действие
 * выглядело по-разному в зависимости от того, пальцем его сделали или кнопкой (замечание
 * владельца 2026-09-15).
 */
private val DURATION = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

private val SLIDE = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = 300,
    easing = FastOutSlowInEasing
)

/** Насколько отступает экран, который остаётся позади: он не уезжает целиком, а сдвигается. */
private const val BEHIND = 4

/** Пришли на экран: соседи проявляются, глубина приезжает справа. */
val entering: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenPlaces()) fadeIn(DURATION) else slideInHorizontally(SLIDE) { width -> width } + fadeIn(DURATION)
}

/** Ушли с экрана: сосед гаснет, а оставшийся позади отступает влево — виден краем. */
val leaving: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenPlaces()) {
        fadeOut(DURATION)
    } else {
        slideOutHorizontally(SLIDE) { width -> -width / BEHIND } + fadeOut(DURATION)
    }
}

/** Вернулись: прежний приходит из-за левого края — тем же путём, каким отступал. */
val returning: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenPlaces()) {
        fadeIn(DURATION)
    } else {
        slideInHorizontally(SLIDE) { width -> -width / BEHIND } + fadeIn(DURATION)
    }
}

/**
 * Возврат назад: верхний уезжает вправо целиком. Он же — то, что человек тянет пальцем:
 * предиктивный жест показывает именно этот переход, поэтому он и должен быть полным сдвигом, а
 * не сжатием со всех сторон.
 */
val goingBack: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenPlaces()) fadeOut(DURATION) else slideOutHorizontally(SLIDE) { width -> width } + fadeOut(DURATION)
}

/**
 * Оба края перехода — места нижней навигации? Тогда это движение вбок, а не вглубь. Спрашивается
 * это у самого перехода, а не назначается каждому экрану: экран не знает, откуда на него пришли,
 * и назначенное ему движение сказало бы неправду при уходе вглубь.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenPlaces(): Boolean =
    initialState.isPlace() && targetState.isPlace()

private fun NavBackStackEntry.isPlace(): Boolean =
    Destination.entries.any { place -> destination.hasRoute(place.route::class) }
