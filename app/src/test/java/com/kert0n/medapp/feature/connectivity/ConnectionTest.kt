package com.kert0n.medapp.feature.connectivity

import com.kert0n.medapp.fixture.FakeConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Возвращение связи — повод для захода (PLAN E4): его слышат, когда связь появилась после потери, и
 * не слышат, когда она просто была.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTest {

    @Test
    fun connectionThatWasThereWhenListeningStartedIsNotAReturn() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeConnection(online = true)
        val heard = mutableListOf<Unit>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { connection.returns().toList(heard) }

        connection.online.value = true

        listening.cancel()
        assertEquals(0, heard.size)
    }

    @Test
    fun connectionThatAppearsAfterItWasLostIsHeardEachTime() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeConnection(online = true)
        val heard = mutableListOf<Unit>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { connection.returns().toList(heard) }

        connection.online.value = false
        connection.online.value = true
        connection.online.value = false
        connection.online.value = true

        listening.cancel()
        assertEquals(2, heard.size)
    }

    @Test
    fun startingOfflineAndGettingConnectionIsAReturn() = runTest(UnconfinedTestDispatcher()) {
        val connection = FakeConnection(online = false)
        val heard = mutableListOf<Unit>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { connection.returns().toList(heard) }

        connection.online.value = true

        listening.cancel()
        assertEquals(1, heard.size)
    }
}
