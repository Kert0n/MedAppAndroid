package com.kert0n.medapp.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Чтение после перечитывания (PLAN E4): ожидание кончается на значении, прочитанном после ответа. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshTest {

    /**
     * Пока сервер не ответил, база не читается вовсе: поток, открытый раньше, отдал бы прежнее
     * число, и экран снял бы ожидание на нём — человек решил бы по тому, что сервер уже поправил.
     */
    @Test
    fun theBaseIsNotReadBeforeTheAnswer() = runTest {
        val answered = CompletableDeferred<Unit>()
        val base = MutableStateFlow(3)
        var opened = false

        val reading = backgroundScope.readAfter(ScreenReading(), { answered.await() }) { base.onStart { opened = true } }
        runCurrent()

        assertEquals(Fresh.Waiting, reading.value)
        assertFalse(opened)

        base.value = 1 // ответ сервера уложен
        answered.complete(Unit)
        runCurrent()

        assertEquals(Fresh.Read(1), reading.value)
    }
}
