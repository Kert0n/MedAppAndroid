package com.kert0n.medapp.domain.medkit

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Ключ — секрет, срок — оценка (PLAN B6, G3). */
class InvitationTest {

    private val issuedAt: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Test
    fun theTermIsAnEstimateFromTheMomentOfIssue() {
        val invitation = Invitation(InvitationKey("ключ"), issuedAt, Duration.ofMinutes(60))

        assertEquals(Instant.parse("2027-03-10T13:00:00Z"), invitation.expiresAround)
    }

    /** Ключ открывает чужую полку: ни `toString` ключа, ни приглашения его не показывают. */
    @Test
    fun theKeyIsNeverPrinted() {
        val invitation = Invitation(InvitationKey("секретный-ключ"), issuedAt, Duration.ofMinutes(60))

        assertFalse(invitation.toString().contains("секретный-ключ"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankKeyIsNoKey() {
        InvitationKey("  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun anInvitationLastsSomeTime() {
        Invitation(InvitationKey("ключ"), issuedAt, Duration.ZERO)
    }
}
