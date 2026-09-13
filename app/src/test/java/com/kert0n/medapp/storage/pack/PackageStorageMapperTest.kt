package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.storage.pack.toStorageEntity
import com.kert0n.medapp.network.server.ResourceVersion
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.toStorageRow
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity

/**
 * Круговое преобразование упаковки: серверная часть и личные сведения хранятся порознь, а
 * собранная обратно пачка равна исходной по каждому полю (PLAN F1).
 */
class PackageStorageMapperTest {

    private val full: Package = pack(
        name = "Парацетамол",
        quantity = tablets("19.5"),
        form = TABLET_FORM,
        category = "Обезболивающие",
        manufacturer = "Завод",
        country = "Россия",
        description = "Таблетки, покрытые оболочкой",
        expiresOn = expiry("2027-03-31"),
        defaultIntakeAmount = dose("0.5"),
        note = "в верхнем ящике",
        price = Money(BigDecimal("199.90")),
        purchasedOn = LocalDate.of(2026, 1, 15),
        openedOn = LocalDate.of(2026, 2, 1),
        templateId = TABLETS_ID
    )

    private fun rowOf(pkg: Package, sync: PackageSyncState = PackageSyncState(pkg.id)) = pkg.toStorageRow(sync)

    @Test
    fun everyFactSurvivesTheRoundTrip() {
        val restored = rowOf(full).toDomain(VOCABULARY)
        assertEquals(full.id, restored.id)
        assertEquals(full.medKit, restored.medKit)
        assertEquals(full.quantity, restored.quantity)
        assertEquals(full.addedAt, restored.addedAt)
        assertEquals(full.templateId, restored.templateId)
        assertEquals(full.facts, restored.facts)
    }

    @Test
    fun absentFactsStayAbsent() {
        val bare = pack(quantity = tablets("1"))
        val restored = rowOf(bare).toDomain(VOCABULARY)
        assertEquals(bare.facts, restored.facts)
        assertNull(restored.facts.expiresOn)
        assertNull(restored.facts.price)
        assertNull(restored.facts.defaultIntakeAmount)
        assertNull(restored.templateId)
    }

    /**
     * Единицу пачки сменил сосед на сервере, а подсказка дозы осталась в старой: она потеряла
     * смысл и не восстанавливается — чтение пачки от чужой правки не ломается.
     */
    @Test
    fun aHintInAForeignUnitIsNotRestored() {
        val row = rowOf(full)
        val relabelled = PackageStorageRow(
            pack = pack(quantity = millilitres("100")).toStorageEntity(PackageSyncState(PACK)),
            record = row.record,
            details = row.details,
            claims = row.claims,
            medKit = row.medKit
        )

        val restored = relabelled.toDomain(VOCABULARY)

        assertEquals(millilitres("100"), restored.quantity)
        assertNull(restored.facts.defaultIntakeAmount)
        assertEquals(full.facts.note, restored.facts.note)
    }

    /** Обвязка доставки едет в колонках, а не в пачке: домен её обратно не получает. */
    @Test
    fun syncStateTravelsInColumnsAndNotInTheDomainPackage() {
        val sync = PackageSyncState(
            packageId = PACK,
            version = ResourceVersion(7),
            claimsVersion = ResourceVersion(3),
            syncedAt = Instant.parse("2026-09-10T12:00:00Z")
        )
        val stored = full.toStorageEntity(sync)
        assertEquals(sync, stored.syncState())
        assertEquals(
            full.facts,
            PackageStorageRow(
                stored,
                full.record.toStorageEntity(),
                full.toDetailsStorageEntity(),
                medKit = medKit().toMedKitStorageEntity()
            ).toDomain(VOCABULARY).facts
        )
    }

    @Test
    fun sharedFactsAreTheServerPartAndNothingElse() {
        assertEquals(full.facts.shared, full.toStorageEntity().sharedFacts(VOCABULARY))
    }

    @Test
    fun syncStateOfAnotherPackageIsRejected() {
        val alien = PackageSyncState(packageId = HOME_KIT, version = ResourceVersion(1))
        val failure = runCatching { full.toStorageEntity(alien) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, failure!!::class)
    }

    /** Запись о коробке — снимок того, что о ней нужно знать истории, и момент появления. */
    @Test
    fun recordCarriesTheSnapshotAndTheMomentOfAppearance() {
        val record = full.record.toStorageEntity()
        assertEquals(full.id, record.id)
        assertEquals(full.name, record.name)
        assertEquals(full.quantity.unit.id, record.unitId)
        assertEquals(full.facts.form?.id, record.formId)
        assertEquals(full.addedAt, record.addedAt)
        assertEquals(full.ref, record.toRef(VOCABULARY))
    }
}
