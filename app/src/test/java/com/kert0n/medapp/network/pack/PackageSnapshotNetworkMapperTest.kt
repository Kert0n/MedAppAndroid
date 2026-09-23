package com.kert0n.medapp.network.pack

import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyMiss
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.server.ResourceVersionNetworkDTO
import com.kert0n.medapp.queue.ResourceVersion
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Снимок с провода собирается в домен по словарю; промах называет единицу (PLAN E4). */
class PackageSnapshotNetworkMapperTest {

    private fun snapshot(unitId: kotlin.uuid.Uuid = TABLETS.id) = PackageSnapshotNetworkDTO(
        pack = PackageNetworkDTO(
            id = PACK,
            name = "Парацетамол",
            amount = "20.000000",
            unitId = unitId,
            formId = TABLET_FORM.id,
            category = "жаропонижающие",
            medKitId = HOME_KIT,
            version = ResourceVersionNetworkDTO(3)
        ),
        claims = ClaimsNetworkDTO(total = "5.000000", mine = "2.000000", version = ResourceVersionNetworkDTO(4))
    )

    @Test
    fun serverStateBecomesAPackageWithItsSyncState() {
        val found = snapshot().toDomain(VOCABULARY, medKit().ref, addedAt = EARLIER, observedAt = LATER)
        assertEquals(PACK, found.pack.id)
        assertEquals(tablets("20"), found.pack.quantity)
        assertEquals(TABLET_FORM, found.pack.facts.form)
        assertEquals("жаропонижающие", found.pack.facts.category)
        assertEquals(0, BigDecimal("3").compareTo(requireNotNull(found.pack.claims).reservedByOthers))
        assertEquals(EARLIER, found.pack.addedAt)
        assertEquals(ResourceVersion(3), found.sync.version)
        assertEquals(ResourceVersion(4), found.sync.claimsVersion)
        assertEquals(LATER, found.sync.syncedAt)
        assertTrue(found.sync.isOnServer)
    }

    @Test
    fun aUnitOutsideTheSnapshotIsAMissThatNamesItself() {
        val stale = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        val miss = assertThrows(VocabularyMiss::class.java) {
            snapshot(unitId = MILLILITRES.id).toDomain(stale, medKit().ref, EARLIER, LATER)
        }
        assertEquals(MILLILITRES.id, miss.id)
        assertEquals(VocabularyMiss.Kind.UNIT, miss.kind)
    }
}
