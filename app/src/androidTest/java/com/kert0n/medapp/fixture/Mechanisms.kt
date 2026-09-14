package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.notification.ReminderOutbox
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Механизмы, которые в приложении живут с процессом, — **запущенные**, как в `MedApp.onCreate`:
 * владелец доставки следит за таблицей обязательств, и показ приходит сам, по сигналу после
 * коммита. Проверка через механизм отличается от проверки через `pass()`: она доказывает не
 * «проход умеет», а «изменение данных → фиксация → пробуждение → системный результат», включая
 * ответ человека, который вклинился посередине.
 *
 * Настоящие потоки Room и настоящий диспетчер: тесты идут в `runBlocking`, а результат ждётся
 * опросом — [await] с именем ожидания, чтобы провал говорил, чего не дождались.
 */
class Mechanisms(scenarios: Scenarios, at: java.time.Instant) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val outbox: ReminderOutbox = ReminderOutbox(
        scenarios.reminderStore, scenarios.notifier, scenarios.reminders, scenarios.freshness,
        scenarios.transactions, Clock.fixed(at, ZoneOffset.UTC), scope
    ).also { it.start() }

    /** Дождаться условия или упасть с именем того, чего ждали. */
    suspend fun await(what: String, timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
        val met = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(20)
            true
        }
        if (met != true) throw AssertionError("не дождались: $what")
    }

    /** Механизмы затихли: число проходов владельца доставки не менялось [quietMillis]. */
    suspend fun settle(quietMillis: Long = 500) {
        var seen = outbox.state.value.passes
        var quietFor = 0L
        while (quietFor < quietMillis) {
            delay(50)
            val now = outbox.state.value.passes
            if (now == seen) quietFor += 50 else { seen = now; quietFor = 0 }
        }
    }

    override fun close() = scope.cancel()
}
