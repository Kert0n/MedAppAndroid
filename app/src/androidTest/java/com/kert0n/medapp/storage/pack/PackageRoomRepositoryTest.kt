package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.feature.packages.PackageQuery
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.FixturePackages
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.settle
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.course.ActivePackageAssignmentStorageEntity
import com.kert0n.medapp.storage.course.toSourceStorageEntities
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.operation.SyncOperationRoomRepository
import com.kert0n.medapp.storage.operation.applySnapshot
import com.kert0n.medapp.storage.operation.syncState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Репозиторий отдаёт домен, а не строки: пачка собирается из трёх таблиц, а оценку количества
 * даёт свёртка незакрытых команд очереди (PLAN E1, H1).
 */
class PackageRoomRepositoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var repository: FixturePackages
    private lateinit var queue: SyncOperationRoomRepository

    private val today = LocalDate.of(2027, 3, 1)
    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val operation: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")
    private val recount: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000092")
    private val later: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000093")

    private val paracetamol = pack(quantity = tablets("20"))

    /** Все запросы к базе — чтобы посчитать, сколько раз список читал очередь. */
    private val counted = mutableListOf<String>()

    /** Снимок с сервера несёт и брони: их версия без самой картины никуда не ездит (PLAN B3). */
    private fun withClaims(quantity: com.kert0n.medapp.domain.value.Quantity) =
        pack(quantity = quantity, claims = Claims(total = BigDecimal("0")))

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase { sql -> synchronized(counted) { counted += sql } }
        repository = database.packageRepository()
        queue = database.queueRepository()
        // Снимок сервера описывает коробку общей полки: местная серверу не принадлежит (PLAN E6).
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
        repository.add(paracetamol)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /**
     * Порченая валюта в колонке — это «цены нет», а не «коробки нет» (issue #40): одна такая
     * строка иначе роняла бы карточку, полку и все лекарства разом.
     *
     * Красная проверка: восстанавливать валюту `Currency.getInstance` без защиты — чтение
     * бросает, и коробка не читается вовсе.
     */
    @Test
    fun aCorruptCurrencyCodeLosesThePriceButNotThePackage() = runTest {
        assertTrue(repository.describe(PACK, paracetamol.facts.copy(price = Money(BigDecimal("320"), Currency.getInstance("RUB")))))
        assertEquals("320", requireNotNull(repository.observe(PACK).first()).facts.price?.amount?.toPlainString())

        database.openHelper.writableDatabase.execSQL("UPDATE package_details SET currency = 'ZZ' WHERE package_id = ?", arrayOf(PACK.toString()))

        val observed = requireNotNull(repository.observe(PACK).first())
        assertNull(observed.facts.price)
        assertEquals(1, repository.list(PackageQuery(), today).first().size)
    }

    /** Наружу уходит проекция — величина с доступностью внутри, а не сущность (PLAN H1). */
    @Test
    fun savedPackageIsObservedAsAProjection() = runTest {
        val observed = requireNotNull(repository.observe(PACK).first())
        assertEquals(paracetamol.facts, observed.facts)
        assertEquals(tablets("20"), observed.quantity)
        assertEquals(paracetamol.projected(), observed)
        assertFalse(observed.hasUnconfirmedChanges)
    }

    /** Список — те же проекции, собранные одним чтением: у каждой пачки своя доступность. */
    @Test
    fun theListCarriesAProjectionPerPackage() = runTest {
        repository.add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("8")))
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)

        val listed = repository.list(PackageQuery(), today).first().associateBy { it.id }

        assertEquals(tablets("17"), listed.getValue(PACK).availability.effective)
        assertTrue(listed.getValue(PACK).hasUnconfirmedChanges)
        assertEquals(tablets("8"), listed.getValue(OTHER_PACK).availability.effective)
        assertFalse(listed.getValue(OTHER_PACK).hasUnconfirmedChanges)
    }

    @Test
    fun withoutQueueTheAmountIsTheConfirmedOne() = runTest {
        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("20"), availability.effective)
        assertEquals(tablets("20"), availability.availableToMe)
        assertEquals(tablets("20"), availability.freeForAnyone)
    }

    /** Незакрытый расход вычитается из подтверждённого остатка ровно один раз (PLAN E1). */
    @Test
    fun unclosedConsumeIsProjectedOnce() = runTest {
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("17"), availability.effective)
    }

    @Test
    fun settledOperationStopsAffectingTheAmount() = runTest {
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        database.syncOperations().settle(operation, SyncOperationStatus.APPLIED)

        assertEquals(
            tablets("20"),
            requireNotNull(repository.observe(PACK).first()).availability.effective
        )
    }

    /** Расход, чей ответ потерялся, из числа не выпадает: устройство знает, что отправило (E1). */
    @Test
    fun operationWithALostAnswerStillCounts() = runTest {
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        database.syncOperations().settle(operation, SyncOperationStatus.PENDING, lastError = "обрыв", at = at, attempted = 1)

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("17"), availability.effective)
        assertEquals(tablets("17"), availability.freeForAnyone)
    }

    /**
     * Список спрашивает очередь одним чтением на всю выборку, а не по запросу на пачку: знание
     * то же, а двести пачек не дают двухсот запросов. Доступности при этом верны у каждой.
     */
    @Test
    fun theListAsksTheQueueOnceForAllPackages() = runTest {
        val third = Uuid.parse("00000000-0000-4000-8000-000000000024")
        repository.add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("8")))
        repository.add(pack(id = third, name = "Аспирин", quantity = tablets("5")))
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        synchronized(counted) { counted.clear() }

        val listed = repository.list(PackageQuery(), today).first().associate { it.id to it.availability.effective }

        assertEquals(tablets("17"), listed[PACK])
        assertEquals(tablets("8"), listed[OTHER_PACK])
        assertEquals(tablets("5"), listed[third])
        val reads = synchronized(counted) { counted.toList() }
            .filter { it.contains("FROM sync_operations") || it.contains("FROM `sync_operations`") }
        assertTrue("чтений sync_operations: ${reads.size} — ${reads}", reads.size <= 2)
    }

    /**
     * Список длиннее одной порции читается по частям, и части не теряются: незакрытая команда
     * каждой пачки входит в её оценку, в какую бы порцию пачка ни попала. Длину списка задают
     * данные — пачки аптечки, — а не код, и запрос `IN (...)` ограничен числом переменных.
     *
     * Красная проверка: взять только первую порцию — пачки из остальных остаются с полным
     * остатком, и оценка врёт ровно на невидимые команды.
     */
    @Test
    fun aListLongerThanOneChunkKeepsEveryPackagesQueue() = runTest {
        val many = 600 // больше одной порции
        repeat(many) { i ->
            val id = Uuid.random()
            repository.add(pack(id = id, name = "Пачка $i", quantity = tablets("5")))
            queue.enqueue(Uuid.random(), PackageSyncCommand.Consume(id, dose("1"), Uuid.random()), at)
        }

        val listed = repository.list(PackageQuery(), today).first().filter { it.id != PACK }

        assertEquals(many, listed.size)
        // У каждой пачки расход на единицу учтён: 5 − 1. Потерянная порция оставила бы пятёрки.
        assertEquals(emptyList<Uuid>(), listed.filter { it.availability.effective != tablets("4") }.map { it.id })
    }

    /**
     * Обвязка синхронизации — своим методом: версии и момент сверки принадлежат доставке, а не
     * пачке, и в проекцию не входят (PLAN E4, H3 №28).
     */
    @Test
    fun syncStateIsObservedByItsOwnMethod() = runTest {
        val sync = PackageSyncState(PACK, ResourceVersion(5), ResourceVersion(2), syncedAt = at)
        repository.applySnapshot(PackageSnapshot(withClaims(tablets("11")), sync), at)

        assertEquals(sync, repository.observeSyncState(PACK).first())
        assertNull(repository.observeSyncState(OTHER_PACK).first())
    }

    /** Пересчёт кладёт разницу поверх увиденного, а более новый расход — поверх него (PLAN E1, C1). */
    @Test
    fun pendingRecountLaysItsDifferenceAndLaterCommandsApplyOnTop() = runTest {
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        // Человек видел 17 (20 без трёх в пути) и насчитал 10.
        queue.enqueue(recount, PackageSyncCommand.CorrectStock(PACK, tablets("17"), tablets("10")), at)
        queue.enqueue(later, PackageSyncCommand.Consume(PACK, dose("2"), OTHER_INTAKE), at)

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("8"), availability.effective)
    }

    @Test
    fun claimsOfOthersReduceWhatIsAvailableToMe() = runTest {
        repository.saveClaims(PACK, Claims(total = BigDecimal("8"), mine = BigDecimal("3")))

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("15"), availability.availableToMe)
    }

    @Test
    fun claimsAreDroppedWhenAccessIsLost() = runTest {
        repository.saveClaims(PACK, Claims(total = BigDecimal("8")))
        repository.saveClaims(PACK, null)

        assertNull(requireNotNull(repository.observe(PACK).first()).claims)
    }

    /** Занятое активным курсом вычитается из свободного, а доступное мне не трогает. */
    @Test
    fun activeCourseAllocationIsSubtractedFromTheFreePart() = runTest {
        givenActiveCourseTaking(doses = 4)

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(tablets("20"), availability.availableToMe)
        assertEquals(tablets("12"), availability.freeForAnyone)
    }

    @Test
    fun hasFreeKeepsOnlyPackagesWithSomethingLeftOver() = runTest {
        val other = pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("8"))
        repository.add(other)
        givenActiveCourseTaking(doses = 10)

        val found = repository.list(
            PackageQuery(filter = PackageQuery.Filter.HasFree),
            today
        ).first().map { it.name }

        assertEquals(listOf("Ибупрофен"), found)
    }

    /**
     * Переименование правит описание и только его: остаток, обвязка синхронизации и брони
     * остаются нынешними, хотя экран загрузил пачку до чужой записи.
     */
    @Test
    fun describingDoesNotWriteBackAStaleAmount() = runTest {
        val sync = PackageSyncState(PACK, version = ResourceVersion(5), syncedAt = at)
        repository.applySnapshot(PackageSnapshot(paracetamol.correctTo(tablets("11")).left(), sync), at)

        val renamed = paracetamol.facts.let { it.copy(shared = it.shared.copy(name = "Панадол")) }

        assertTrue(repository.describe(PACK, renamed))

        val described = requireNotNull(repository.find(PACK))
        assertEquals("Панадол", described.name)
        assertEquals(tablets("11"), described.quantity)
        assertEquals(sync, requireNotNull(database.packages().find(PACK)).pack.syncState())
    }

    @Test
    fun snapshotKeepsLocalDetailsAndPreconditions() = runTest {
        val sync = PackageSyncState(PACK, version = ResourceVersion(5), claimsVersion = ResourceVersion(2), syncedAt = at)
        repository.applySnapshot(PackageSnapshot(withClaims(tablets("11")), sync), at)

        assertEquals(tablets("11"), requireNotNull(repository.find(PACK)).quantity)
        assertEquals(sync, requireNotNull(database.packages().find(PACK)).pack.syncState())
        assertEquals(paracetamol.addedAt, requireNotNull(repository.find(PACK)).addedAt)
    }

    /**
     * Сосед сменил единицу пачки на сервере: незакрытый расход и выделение курса остались в
     * таблетках, а пачка теперь в миллилитрах. Чтение доступности не бросает: команда в чужой
     * единице в число не входит, выделение в чужой единице пачку не занимает (PLAN E1, D4).
     */
    @Test
    fun aUnitChangedOnTheServerDoesNotBreakTheReading() = runTest {
        queue.enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        givenActiveCourseTaking(doses = 4)
        val sync = PackageSyncState(PACK, version = ResourceVersion(5), claimsVersion = ResourceVersion(2), syncedAt = at)
        repository.applySnapshot(
            PackageSnapshot(pack(quantity = millilitres("100"), claims = Claims(BigDecimal("0"))), sync),
            at
        )

        val availability = requireNotNull(repository.observe(PACK).first()).availability
        assertEquals(millilitres("100"), availability.effective)
        assertEquals(millilitres("100"), availability.availableToMe)
        assertEquals(millilitres("100"), availability.freeForAnyone)
        assertEquals(listOf(PACK), repository.list(PackageQuery(filter = PackageQuery.Filter.HasFree), today).first().map { it.id })
    }

    private suspend fun givenActiveCourseTaking(doses: Int) {
        val plan = activeCourse(sources = listOf(source(PACK, doses)))
        database.courses().saveCourse(
            plan.toCourseStorageEntity(),
            emptyList(),
            plan.medicine.toSourceStorageEntities(COURSE)
        )
        database.courses().assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))
    }
}
