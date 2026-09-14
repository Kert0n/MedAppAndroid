package com.kert0n.medapp.queue

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цикл владельца — один на очередь, доставку и сверку (PLAN C1 «Цикл владельца — один»): сигналы
 * сворачиваются, проход один за раз, сбой записан и повторяется по сроку, готовность — от первого
 * значения потока. Пока копий было три, они расходились: сверка после сбоя ждала неизвестно чего.
 */
class OutboxLoopTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    private class TestClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    /** Поток сигналов, как у хранилища: первое значение — «встал», дальше — изменения. */
    private val signals = MutableSharedFlow<Unit>()

    private fun ready() = signals.onStart { emit(Unit) }

    @Test
    fun readinessComesFromTheFirstValueNotFromTime() = runTest {
        var passes = 0
        val loop = OutboxLoop(ready(), Duration.ofMinutes(1), Clock.fixed(now, ZoneOffset.UTC), backgroundScope, initialPass = false) { passes++; null }
        assertFalse(loop.ready.value)

        loop.start()
        runCurrent()

        assertTrue("готовность — из первого значения потока", loop.ready.value)
        assertEquals("первое значение — не изменение: прохода по нему нет", 0, passes)
    }

    /**
     * **Начальный проход — не раньше, чем встал наблюдатель.** Иначе коммит между чтением
     * начального прохода и постановкой наблюдателя не давал сигнала никому: работа лежала до
     * чужого повода. У Room наблюдатель встаёт не мгновенно — здесь это `delay` перед первым
     * значением.
     */
    @Test
    fun theInitialPassDoesNotRunBeforeTheObserverStands() = runTest {
        lateinit var loop: OutboxLoop
        var readyAtPass: Boolean? = null
        loop = OutboxLoop(signals.onStart { delay(100); emit(Unit) }, Duration.ofMinutes(1), Clock.fixed(now, ZoneOffset.UTC), backgroundScope) {
            readyAtPass = loop.ready.value
            null
        }
        loop.start()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("начальный проход шёл до того, как встал наблюдатель", true, readyAtPass)
    }

    @Test
    fun signalsDuringAPassCollapseIntoOneMore() = runTest {
        val gate = CompletableDeferred<Unit>()
        var passes = 0
        val loop = OutboxLoop(ready(), Duration.ofMinutes(1), Clock.fixed(now, ZoneOffset.UTC), backgroundScope) {
            passes++
            if (passes == 1) gate.await()
            null
        }
        loop.start()
        runCurrent()
        assertEquals(1, passes)

        repeat(5) { signals.emit(Unit) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, passes)
        assertEquals(2, loop.state.value.passes)
    }

    @Test
    fun aFailureIsRecordedAndRetriedAfterTheTerm() = runTest {
        val clock = TestClock(now)
        var passes = 0
        val loop = OutboxLoop(ready(), Duration.ofMinutes(1), clock, backgroundScope) {
            passes++
            if (passes == 1) error("база бросила")
            null
        }
        loop.start()
        runCurrent()

        assertEquals(1, passes)
        assertNotNull(loop.state.value.lastFailure)
        assertEquals(now.plusSeconds(60), loop.state.value.nextRunAt)

        clock.now = now.plusSeconds(60)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("сбой повторяется сам, без чужого сигнала", 2, passes)
        assertNull(loop.state.value.lastFailure)
    }

    @Test
    fun thePassNamesWhenToComeBackAndTheTimerObeys() = runTest {
        val clock = TestClock(now)
        var passes = 0
        val loop = OutboxLoop(ready(), Duration.ofMinutes(1), clock, backgroundScope) {
            passes++
            if (passes == 1) now.plusSeconds(30) else null
        }
        loop.start()
        runCurrent()
        assertEquals(now.plusSeconds(30), loop.state.value.nextRunAt)

        clock.now = now.plusSeconds(29)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, passes)
        clock.now = now.plusSeconds(30)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, passes)
        assertNull("ждать больше нечего", loop.state.value.nextRunAt)
    }

    @Test
    fun runNowDuringAPassIsNotLostAndStartIsIdempotent() = runTest {
        val gate = CompletableDeferred<Unit>()
        var passes = 0
        val loop = OutboxLoop(ready(), Duration.ofMinutes(1), Clock.fixed(now, ZoneOffset.UTC), backgroundScope) {
            passes++
            if (passes == 1) gate.await()
            null
        }
        loop.start()
        loop.start()
        runCurrent()
        loop.runNow()
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, passes)
    }
}
