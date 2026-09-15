package com.kert0n.medapp.fixture

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ожидание условия — с именем того, чего ждут, и с провалом по сроку: цикл «пока не наступило или
 * не вышло время», кончающийся без утверждения, засчитал бы за ответ то, что случилось само
 * (C1 «Цикл владельца — сначала наблюдатель»). Одно на все проверки механизмов — и на те, что
 * строят владельцев через [Mechanisms], и на те, что получают их из Hilt.
 *
 * **Ждать можно только в настоящем времени.** Механизмы живут на `Dispatchers.IO` и в потоках
 * Room, то есть по часам машины; под виртуальным временем `runTest` и [delay], и срок
 * [withTimeoutOrNull] мгновенны — пять секунд ожидания истекают раньше, чем настоящая работа
 * успевает начаться, и проверка становится гонкой: зелёной на свободной машине и красной на
 * занятой. Поэтому виртуальное время здесь не смягчается сроком, а называется ошибкой.
 */
suspend fun await(what: String, timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
    requireRealTime()
    val met = withTimeoutOrNull(timeoutMillis) {
        while (!condition()) delay(20)
        true
    }
    if (met != true) throw AssertionError("не дождались: $what")
}

/**
 * Проверка идёт по часам машины, а не по виртуальным. Сказать об этом надо сразу и на пороге:
 * иначе ошибка проявится нестабильным провалом в чужом классе через полгода — и не в том, кто
 * её сделал.
 *
 * Зовут это все двери, которые меряют настоящее время: [await] и `Mechanisms.settle`.
 */
suspend fun requireRealTime() {
    val dispatcher = coroutineContext[ContinuationInterceptor]
    check(dispatcher !is TestDispatcher) {
        "ожидание механизма идёт по настоящим часам: замените runTest на runBlocking — " +
            "под виртуальным временем срок ожидания истекает мгновенно (фикстура Mechanisms)"
    }
}
