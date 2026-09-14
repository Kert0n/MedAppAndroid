package com.kert0n.medapp.queue

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Интервал — целые минуты с пределами на самом типе (PLAN E4): секунд у него нет, и хранилищу с
 * планировщиком невалидное не передать.
 */
class SyncIntervalTest {

    @Test
    fun boundsAreInclusive() {
        assertEquals(Duration.ofMinutes(15), SyncInterval(15).duration)
        assertEquals(Duration.ofHours(8), SyncInterval(480).duration)
    }

    @Test
    fun fourteenMinutesAndFourHundredEightyOneAreRefused() {
        assertTrue(runCatching { SyncInterval(14) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncInterval(481) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun defaultIsAnHour() {
        assertEquals(60L, SyncInterval.DEFAULT.minutes)
        assertEquals(Duration.ofHours(1), SyncInterval.DEFAULT.duration)
    }
}
