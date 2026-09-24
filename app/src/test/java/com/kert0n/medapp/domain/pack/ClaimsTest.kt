package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.fixture.pack
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimsTest {

    @Test
    fun claimsMayExceedWhatIsLeftInThePack() {
        // Законное состояние: сколько из своей брони оставить, решает её владелец, а не сервер.
        val claims = Claims(total = BigDecimal("30"), mine = BigDecimal("10"))
        assertEquals(BigDecimal("20"), claims.reservedByOthers)
    }

    @Test
    fun staleSnapshotWhereMyClaimLooksBiggerGivesNoNegativeShare() {
        // `mine` отстаёт от локального выделения ровно на то, что ещё не уехало.
        val claims = Claims(total = BigDecimal("5"), mine = BigDecimal("7"))
        assertEquals(0, claims.reservedByOthers.signum())
    }

    @Test
    fun nothingClaimedByMeIsNullAndNotZero() {
        val claims = Claims(total = BigDecimal("5"), mine = null)
        assertEquals(BigDecimal("5"), claims.reservedByOthers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTotalIsRejected() {
        Claims(total = BigDecimal("-1"), mine = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sevenFractionDigitsAreRejected() {
        Claims(total = BigDecimal("1.0000001"), mine = null)
    }

}
