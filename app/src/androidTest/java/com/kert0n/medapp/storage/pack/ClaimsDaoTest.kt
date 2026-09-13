package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY

/**
 * Брони хранятся своей строкой и со своей версией: картину двигают чужие действия, а версия —
 * предусловие запроса и живёт в колонках пачки (PLAN F1, D4).
 */
class ClaimsDaoTest {

    private lateinit var database: MedAppDatabase
    private val packages get() = database.packages()

    private val shared = pack(quantity = tablets("20"))

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        packages.save(shared)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun packageWithoutClaimsRowKnowsNothingAboutThem() = runTest {
        assertNull(requireNotNull(packages.find(PACK)).toDomain(VOCABULARY).claims)
    }

    @Test
    fun claimsComeBackWithBothNumbers() = runTest {
        val claims = Claims(total = BigDecimal("7.5"), mine = BigDecimal("2.5"))
        packages.upsertClaims(claims.toStorageEntity(PACK))

        val restored = requireNotNull(requireNotNull(packages.find(PACK)).toDomain(VOCABULARY).claims)
        assertEquals(claims, restored)
        assertEquals(BigDecimal("5.0"), restored.reservedByOthers)
    }

    @Test
    fun claimingNothingIsNotTheSameAsClaimingZero() = runTest {
        packages.upsertClaims(Claims(total = BigDecimal("4")).toStorageEntity(PACK))
        assertNull(requireNotNull(requireNotNull(packages.find(PACK)).toDomain(VOCABULARY).claims).mine)
    }

    /** Версия картины — предусловие: она едет в колонках пачки, а не в строке броней. */
    @Test
    fun claimsVersionLivesWithTheOtherPreconditions() = runTest {
        val sync = PackageSyncState(
            packageId = PACK,
            version = ResourceVersion(4),
            claimsVersion = ResourceVersion(9),
            syncedAt = Instant.parse("2026-09-10T12:00:00Z")
        )
        packages.upsertServerPart(shared.toStorageEntity(sync))
        packages.upsertClaims(Claims(total = BigDecimal("1")).toStorageEntity(PACK))

        assertEquals(sync, requireNotNull(packages.find(PACK)).pack.syncState())
    }

    @Test
    fun droppedClaimsLeaveNoPictureAtAll() = runTest {
        packages.upsertClaims(Claims(total = BigDecimal("3")).toStorageEntity(PACK))
        packages.deleteClaims(PACK)

        val restored = requireNotNull(packages.find(PACK)).toDomain(VOCABULARY)
        assertNull(restored.claims)
    }
}
