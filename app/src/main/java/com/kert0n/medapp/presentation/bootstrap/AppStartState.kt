package com.kert0n.medapp.presentation.bootstrap

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.feature.bootstrap.AppStart

/**
 * С чего начинается приложение — глазами экрана. Случая четыре, и каждый ведёт человека к своему
 * действию: при [Checking] нажимать нечего, при [Setup] — «повторить», при [KeyLost] нужно его
 * решение, при [Ready] — приложение.
 *
 * [Checking] здесь, а не у сценария: сценарий таким не отвечает никогда — проверка длится, пока он
 * не ответил, и видна она только на экране (PLAN H1).
 *
 * [KeyLost] отделён от [Setup] намеренно: сохранённое есть, но не открывается, и молча завести
 * вторую учётку поверх локальных данных нельзя — приложение показывает состояние и спрашивает
 * (PLAN G2). Склеенные, эти два случая дали бы «повторить», который перерегистрирует.
 */
sealed interface AppStartState {

    data object Checking : AppStartState

    /** Настройка не прошла, и названо почему; повтор осмыслен по правилам самой причины. */
    data class Setup(val reason: Unavailability) : AppStartState

    data object KeyLost : AppStartState

    data object Ready : AppStartState
}

/** Исход начала — в состояние экрана: случаи те же, и различать их дважды нечем. */
fun AppStart.Outcome.toAppStartState(): AppStartState = when (this) {
    AppStart.Outcome.Ready -> AppStartState.Ready
    AppStart.Outcome.KeyLost -> AppStartState.KeyLost
    is AppStart.Outcome.Setup -> AppStartState.Setup(reason)
}
