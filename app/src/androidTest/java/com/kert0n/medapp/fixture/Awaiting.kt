package com.kert0n.medapp.fixture

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ожидание условия — с именем того, чего ждут, и с провалом по сроку: цикл «пока не наступило или
 * не вышло время», кончающийся без утверждения, засчитал бы за ответ то, что случилось само
 * (C1 «Цикл владельца — сначала наблюдатель»). Одно на все проверки механизмов — и на те, что
 * строят владельцев через [Mechanisms], и на те, что получают их из Hilt.
 */
suspend fun await(what: String, timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
    val met = withTimeoutOrNull(timeoutMillis) {
        while (!condition()) delay(20)
        true
    }
    if (met != true) throw AssertionError("не дождались: $what")
}
