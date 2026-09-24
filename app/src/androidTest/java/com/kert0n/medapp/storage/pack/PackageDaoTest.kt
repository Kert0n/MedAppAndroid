package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.fileDatabase
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.reopenFileDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Локальные сведения об упаковке переживают снимок сервера: серверная часть переписывается
 * целиком, а строка деталей своя и создаётся всегда (PLAN F1, E4).
 */
class PackageDaoTest {

    private lateinit var database: MedAppDatabase
    private val packages get() = database.packages()

    private val local = pack(
        quantity = tablets("20"),
        expiresOn = expiry("2027-03-31"),
        defaultIntakeAmount = dose("0.5"),
        note = "в верхнем ящике",
        price = Money(BigDecimal("199.90"))
    )

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        // Снимок сервера описывает коробку общей полки: местная серверу не принадлежит (PLAN E6).
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun savedPackageComesBackWholeFromTwoTables() = runTest {
        packages.save(local)
        val restored = requireNotNull(packages.find(PACK)).toDomain(VOCABULARY)
        assertEquals(local.facts, restored.facts)
        assertEquals(local.quantity, restored.quantity)
        assertEquals(local.addedAt, restored.addedAt)
    }

    @Test
    fun snapshotOfAnUnknownPackageCreatesItsDetailsRow() = runTest {
        val observed = Instant.parse("2026-09-10T12:00:00Z")
        packages.applySnapshot(local.toStorageEntity(), claims = null, observedAt = observed)
        val restored = requireNotNull(packages.find(PACK)).toDomain(VOCABULARY)
        assertEquals(observed, restored.addedAt)
        assertNull(restored.facts.expiresOn)
        assertNull(restored.facts.note)
    }

    /** Повторный снимок меняет серверные поля и не трогает срок годности, заметку и цену. */
    @Test
    fun repeatedServerSnapshotKeepsLocalDetails() = runTest {
        packages.save(local)

        val fromServer = local.correctTo(tablets("12")).left().describe(
            local.facts.copy(shared = local.facts.shared.copy(name = "Paracetamol"))
        )
        packages.applySnapshot(
            fromServer.toStorageEntity(),
            claims = null,
            observedAt = Instant.parse("2026-09-11T12:00:00Z")
        )

        val restored = requireNotNull(packages.find(PACK)).toDomain(VOCABULARY)
        assertEquals("Paracetamol", restored.name)
        assertEquals(tablets("12"), restored.quantity)
        assertEquals(local.facts.expiresOn, restored.facts.expiresOn)
        assertEquals(local.facts.note, restored.facts.note)
        assertEquals(local.facts.price, restored.facts.price)
        assertEquals(local.facts.defaultIntakeAmount, restored.facts.defaultIntakeAmount)
        assertEquals(local.addedAt, restored.addedAt)
    }

    /**
     * Половины снимка применяются порознь: запоздалая серверная часть не откатывает картину
     * броней, и наоборот. Версии независимы — их двигают разные команды (PLAN B3, E1).
     *
     * Красная проверка: одна проверка версии на обе половины роняет оба случая.
     */
    @Test
    fun anOlderHalfOfTheSnapshotDoesNotRollBackTheFresherOne() = runTest {
        givenSnapshot(version = 10, claimsVersion = 5, quantity = tablets("20"), total = "5")

        // Пачка той же версии, картина броней — запоздалая: брони и их версия остаются прежними.
        val applied = applySnapshot(version = 10, claimsVersion = 4, quantity = tablets("12"), total = "4")

        assertEquals(SnapshotApplied(pack = true, claims = false), applied)
        val row = requireNotNull(packages.find(PACK))
        assertEquals(tablets("12"), row.toDomain(VOCABULARY).quantity)
        assertEquals(BigDecimal("5"), requireNotNull(row.claims).toDomain().total)
        assertEquals(ResourceVersion(5), row.pack.syncState().claimsVersion)
    }

    @Test
    fun afresherClaimsHalfLaysDownWhileTheOlderPackageHalfDoesNot() = runTest {
        givenSnapshot(version = 10, claimsVersion = 5, quantity = tablets("20"), total = "5")

        val applied = applySnapshot(version = 9, claimsVersion = 6, quantity = tablets("12"), total = "6")

        assertEquals(SnapshotApplied(pack = false, claims = true), applied)
        val row = requireNotNull(packages.find(PACK))
        assertEquals(tablets("20"), row.toDomain(VOCABULARY).quantity)
        assertEquals(ResourceVersion(10), row.pack.syncState().version)
        assertEquals(BigDecimal("6"), requireNotNull(row.claims).toDomain().total)
        assertEquals(ResourceVersion(6), row.pack.syncState().claimsVersion)
    }

    /** Картина броней без своей версии не читалась: её не кладут и прежнюю версию не трогают. */
    @Test
    fun aSnapshotWithoutClaimsVersionLeavesTheClaimsHalfAlone() = runTest {
        givenSnapshot(version = 10, claimsVersion = 5, quantity = tablets("20"), total = "5")

        val applied = packages.applySnapshot(
            pack(quantity = tablets("12")).toStorageEntity(
                PackageSyncState(PACK, version = ResourceVersion(11))
            ),
            claims = null,
            observedAt = observed
        )

        assertEquals(SnapshotApplied(pack = true, claims = false), applied)
        val row = requireNotNull(packages.find(PACK))
        assertEquals(BigDecimal("5"), requireNotNull(row.claims).toDomain().total)
        assertEquals(ResourceVersion(5), row.pack.syncState().claimsVersion)
    }

    /**
     * Версия картины броней описывает ту картину, что лежит рядом. Дверь, пишущая версию без
     * картины, эту пару разводит: запоздалая версия садится на свежие брони, и следующий снимок
     * — уже по правилам — принимает устаревшую картину как новость (PLAN B3, E1).
     *
     * Красная проверка: дверь, пишущая серверную строку вместе с `claims_version`, краснит это.
     */
    @Test
    fun noDoorMovesTheClaimsVersionWithoutTheClaims() = runTest {
        givenSnapshot(version = 10, claimsVersion = 5, quantity = tablets("20"), total = "8")

        // Снимок пачки версии 10 с запоздалой версией броней 4 — картины броней он не несёт.
        packages.applySnapshot(
            pack(quantity = tablets("12")).toStorageEntity(
                PackageSyncState(PACK, version = ResourceVersion(10), claimsVersion = ResourceVersion(4))
            ),
            claims = null,
            observedAt = observed
        )

        val row = requireNotNull(packages.find(PACK))
        assertEquals(ResourceVersion(5), row.pack.syncState().claimsVersion)
        assertEquals(BigDecimal("8"), requireNotNull(row.claims).toDomain().total)
    }

    private val observed = Instant.parse("2026-09-11T12:00:00Z")

    private suspend fun givenSnapshot(version: Long, claimsVersion: Long, quantity: Quantity, total: String) {
        applySnapshot(version, claimsVersion, quantity, total)
    }

    private suspend fun applySnapshot(
        version: Long,
        claimsVersion: Long,
        quantity: Quantity,
        total: String
    ): SnapshotApplied = packages.applySnapshot(
        pack(quantity = quantity).toStorageEntity(
            PackageSyncState(
                PACK,
                version = ResourceVersion(version),
                claimsVersion = ResourceVersion(claimsVersion)
            )
        ),
        Claims(total = BigDecimal(total)).toStorageEntity(PACK),
        observedAt = observed
    )

    @Test
    fun packagesOfAMedKitAreObservable() = runTest {
        packages.save(local)
        val other = pack(id = OTHER_PACK, name = "Ибупрофен")
        packages.save(other)

        val seen = packages.observeOfMedKit(HOME_KIT).first().map { it.toDomain(VOCABULARY).name }
        assertEquals(listOf("Ибупрофен", "Парацетамол"), seen)
    }

    @Test
    fun writtenPackageSurvivesClosingTheDatabase() = runTest {
        val name = "survives.db"
        val first = fileDatabase(name)
        first.packages().save(local)
        first.close()

        val second = reopenFileDatabase(name)
        try {
            val restored = requireNotNull(second.packages().find(PACK)).toDomain(VOCABULARY)
            assertEquals(local.facts, restored.facts)
            assertEquals(local.quantity, restored.quantity)
        } finally {
            second.close()
        }
    }
}
