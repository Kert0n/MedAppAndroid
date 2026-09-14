package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.Doses
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Брони после изменения выделений — разницей: неизменное не едет, ноль снимает (PLAN D5, E2). */
class ClaimChangesTest {

    private val before = activeCourse(totalDoses = 10, sources = listOf(source(PACK, 8), source(OTHER_PACK, 2)))
    private val at: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Test
    fun aSmallerAllocationTravelsAsANewClaimAndZeroAsARelease() {
        val clamped = before.clamped(Doses(10), availability(PACK to tablets("12"), OTHER_PACK to tablets("20")), at)

        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("12"))), clamped.claimChangesSince(before))

        val emptied = before.clamped(Doses(10), availability(PACK to tablets("1"), OTHER_PACK to tablets("20")), at)
        assertEquals(listOf(PackageSyncCommand.ReleaseClaim(PACK)), emptied.claimChangesSince(before))
    }

    @Test
    fun unchangedAllocationsSendNothing() {
        assertEquals(emptyList<PackageSyncCommand>(), before.claimChangesSince(before))
        val same = before.clamped(Doses(10), availability(PACK to tablets("25"), OTHER_PACK to tablets("20")), at)
        assertEquals(emptyList<PackageSyncCommand>(), same.claimChangesSince(before))
    }

    @Test
    fun aDetachedSourceIsReleased() {
        val detached = before.detach(source(OTHER_PACK, 2).pkg, at)
        assertEquals(listOf(PackageSyncCommand.ReleaseClaim(OTHER_PACK)), detached.claimChangesSince(before))
    }
}
