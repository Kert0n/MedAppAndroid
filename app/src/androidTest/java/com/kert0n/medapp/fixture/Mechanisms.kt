package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.notification.NotificationUpkeep
import com.kert0n.medapp.feature.notification.ReminderOutbox
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Механизмы, которые в приложении живут с процессом, — **запущенные**, как в `MedApp.onCreate`:
 * владелец доставки следит за таблицей обязательств, сверка — за их основаниями, и показ приходит
 * сам, по сигналу после коммита. Проверка через механизм отличается от проверки через `pass()`: она доказывает не
 * «проход умеет», а «изменение данных → фиксация → пробуждение → системный результат», включая
 * ответ человека, который вклинился посередине.
 *
 * Настоящие потоки Room и настоящий диспетчер: тесты идут в `runBlocking`, а результат ждётся
 * опросом — [await] с именем ожидания, чтобы провал говорил, чего не дождались.
 */
class Mechanisms(scenarios: Scenarios, at: java.time.Instant) : AutoCloseable {

    /**
     * Время жизни механизмов — у проверки: владелец, которого тест строит сам (со своими часами
     * или своей сверкой), живёт здесь же и гаснет в [close] вместе с остальными, а не переживает
     * закрытую базу и не будит соседние проверки.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val outbox: ReminderOutbox = ReminderOutbox(
        scenarios.reminderStore, scenarios.notifier, scenarios.reminders, scenarios.freshness,
        scenarios.transactions, Clock.fixed(at, ZoneOffset.UTC), scope
    ).also { it.start() }

    val upkeep: NotificationUpkeep = NotificationUpkeep(
        scenarios.reminderStore, scenarios.notificationReconciliation, Clock.fixed(at, ZoneOffset.UTC), scope
    ).also { it.start() }

    init {
        // Наблюдатели таблиц встают в своих корутинах: запись, сделанная раньше, сигнала не даст.
        // Проверка ждёт именно сигнала, поэтому ждёт готовности — её сообщает сам цикл владельца.
        kotlinx.coroutines.runBlocking {
            await("механизмы встали") { outbox.ready.value && upkeep.ready.value }
        }
    }

    /** Дождаться условия или упасть с именем того, чего ждали — то же [await], что у всех проверок. */
    suspend fun await(what: String, timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) =
        com.kert0n.medapp.fixture.await(what, timeoutMillis, condition)

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
