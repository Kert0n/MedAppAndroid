package com.kert0n.medapp.fixture

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Проверка смотрит на экран так же, как человек. Состояние `ViewModel` собрано
 * `stateIn(WhileSubscribed)`: без подписчика оно молчит и остаётся начальным, и проверка без
 * [watching] увидела бы вечную загрузку — не потому, что экран сломан, а потому, что на него
 * никто не смотрел.
 *
 * `runBlocking`, а не `runTest`: у состояния экрана есть собственное время — подписка, первое
 * значение, ответ сценария, — и виртуальное время `runTest` ломает и потоки Room, и ожидания
 * (тот же довод, что у фикстур `Mechanisms` в B20).
 */
fun <T, R> watching(state: StateFlow<T>, block: suspend (StateFlow<T>) -> R): R = runBlocking {
    val eyes = launch { state.collect { } }
    try {
        block(state)
    } finally {
        eyes.cancel()
    }
}

/**
 * Ждёт того состояния, о котором проверка и написана. Экран отвечает не мгновенно: между
 * действием и новым значением стоят сценарий и поток из базы.
 */
suspend fun <T> StateFlow<T>.awaiting(timeout: Duration = 5.seconds, predicate: (T) -> Boolean): T =
    withTimeoutOrNull(timeout) { first(predicate) }
        ?: error("состояние так и не стало ожидаемым, осталось $value")
