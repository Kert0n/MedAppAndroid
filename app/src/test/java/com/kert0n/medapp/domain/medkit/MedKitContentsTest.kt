package com.kert0n.medapp.domain.medkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Содержимое полки — величина со своими границами (PLAN D2). */
class MedKitContentsTest {

    @Test
    fun expiredNeverExceedsPackages() {
        assertNotNull(runCatching { MedKitContents(packages = 1, expired = 2) }.exceptionOrNull())
        assertNotNull(runCatching { MedKitContents(packages = -1, expired = 0) }.exceptionOrNull())
        assertNotNull(runCatching { MedKitContents(packages = 3, expired = -1) }.exceptionOrNull())
    }

    @Test
    fun emptinessAndExpiryAreReadOffTheCounts() {
        assertTrue(MedKitContents.EMPTY.isEmpty)
        assertFalse(MedKitContents.EMPTY.hasExpired)
        val shelf = MedKitContents(packages = 3, expired = 1)
        assertFalse(shelf.isEmpty)
        assertTrue(shelf.hasExpired)
        assertEquals(MedKitContents(3, 1), shelf)
    }
}
