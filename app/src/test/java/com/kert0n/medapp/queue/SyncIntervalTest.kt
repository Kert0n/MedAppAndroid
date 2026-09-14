package com.kert0n.medapp.queue

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пределы интервала — на самом типе (PLAN E4): хранилищу и планировщику невалидное не передать. */
class SyncIntervalTest {

    @Test
    fun boundsAreInclusive() {
        assertEquals(Duration.ofMinutes(15), SyncInterval(Duration.ofMinutes(15)).duration)
        assertEquals(Duration.ofHours(8), SyncInterval(Duration.ofHours(8)).duration)
    }

    @Test
    fun fourteenMinutesAndNineHoursAreRefused() {
        assertTrue(runCatching { SyncInterval(Duration.ofMinutes(14)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncInterval(Duration.ofHours(9)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun defaultIsAnHour() {
        assertEquals(Duration.ofHours(1), SyncInterval.DEFAULT.duration)
    }
}
