package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.pack.PackageEnding
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.Take
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageAdjustment
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.pack.SnapshotApplied
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Хранение в памяти для проверок представления: базы там нет вовсе, а поведение репозитория
 * нужно настоящее — записанное видно следующим чтением, и переход применяется к тому, что лежит
 * внутри, а не к тому, что подставил тест (PLAN F5).
 *
 * Что подделка **не** обещает: порядок списка и сам подбор по фильтру принадлежат запросу базы и
 * проверяются на ней (`PackageQueryDaoTest`), оценка очереди — свёртке команд (`PackageQueueState`),
 * откат половины записи — транзакции. Здесь отвечают за состав и за то, что запись видна.
 */
class FakePackages(vararg packs: Package) : PackageStorageRepository {

    private val stored = LinkedHashMap<Uuid, Package>()

    private val changes = MutableStateFlow(0)

    /** Что записано: тест спрашивает хранилище, а не следит за вызовами. */
    val packages: List<Package> get() = stored.values.toList()

    /** Незакрытые изменения по коробкам — их подделке называет тест: очереди здесь нет. */
    val changing = mutableSetOf<Uuid>()

    /** Кто держит коробку и когда из неё брали: это знает не коробка, а те, кто её читал (D4). */
    val heldBy = mutableMapOf<Uuid, Uuid>()
    val lastUsed = mutableMapOf<Uuid, Instant>()

    init {
        packs.forEach { stored[it.id] = it }
    }

    override fun observe(id: Uuid): Flow<PackageProjection?> = changes.map { stored[id]?.seen() }

    override suspend fun find(id: Uuid): Package? = stored[id]

    override suspend fun projection(id: Uuid): PackageProjection? = stored[id]?.seen()

    override fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>> =
        changes.map { matching(query).map { it.seen() } }

    override suspend fun add(pkg: Package, sync: PackageSyncState) = write(pkg)

    override suspend fun describe(packageId: Uuid, facts: PackageFacts): Boolean =
        change(packageId) { it.describe(facts) }

    override suspend fun end(ending: PackageEnding, at: Instant): Boolean = forget(ending.pkg.id)

    override suspend fun answersToServer(packageId: Uuid): Boolean =
        stored[packageId]?.medKit?.answersToServer == true

    override suspend fun availabilityFor(course: Course): Availability =
        Availability.from(course.sources.mapNotNull { stored[it.pkg.id]?.availability() })

    override suspend fun mark(packageId: Uuid, status: PackageStatus, by: Uuid): Boolean =
        change(packageId) {
            when (status) {
                PackageStatus.CHANGING -> it.markChanging(by)
                PackageStatus.REMOVING -> it.markRemoving(by)
                PackageStatus.LOST -> it.markLost(by)
                PackageStatus.ACTIVE -> error("пометку снимает закрытие команды, а не сценарий")
            }
        }

    override suspend fun contentsOf(medKitId: Uuid): List<Package> =
        stored.values.filter { it.medKit.id == medKitId }

    override suspend fun applySnapshot(snapshot: PackageSnapshot, observedAt: Instant): SnapshotApplied =
        SnapshotApplied(pack = false, claims = false)

    override fun observeSyncState(id: Uuid): Flow<PackageSyncState?> =
        changes.map { stored[id]?.let { PackageSyncState(it.id) } }

    override suspend fun saveClaims(packageId: Uuid, claims: Claims?) = Unit

    override suspend fun adjust(
        adjustment: PackageAdjustment,
        reallocation: CourseReallocation?,
        at: Instant
    ): Boolean {
        val pkg = stored[adjustment.packageId] ?: return false
        when (val after = adjustment.applyTo(pkg)) {
            is PackageAfter.Left -> write(after.pkg)
            is PackageAfter.Ended -> forget(pkg.id)
        }
        return true
    }

    /** Коробка, лежавшая до прихода человека: подготовка теста, а не действие сценария. */
    fun lying(vararg packs: Package): FakePackages {
        packs.forEach { write(it) }
        return this
    }

    /** «Коробку убрали, пока экран был открыт» — та же дверь, что у сценария, но без него. */
    fun forget(packageId: Uuid): Boolean {
        val removed = stored.remove(packageId) != null
        if (removed) changes.value++
        return removed
    }

    private fun matching(query: PackageQuery): List<Package> = stored.values.filter { pkg ->
        (query.medKitId == null || pkg.medKit.id == query.medKitId) &&
            (query.searchText.isEmpty() || pkg.name.lowercase().contains(query.searchText))
    }

    private fun Package.availability(): PackageAvailability = PackageAvailability(this, effective = quantity)

    private fun Package.seen(): PackageProjection = projection(
        availability = availability(),
        hasUnconfirmedChanges = id in changing,
        holdingCourseId = heldBy[id],
        lastUsedAt = lastUsed[id]
    )

    private fun write(pkg: Package) {
        stored[pkg.id] = pkg
        changes.value++
    }

    private fun change(packageId: Uuid, transition: (Package) -> Package): Boolean {
        val pkg = stored[packageId] ?: return false
        write(transition(pkg))
        return true
    }
}

/**
 * Аптечки в памяти. Содержимое полки считает тот, кто читал коробки, и приносит полке (D2) —
 * здесь его приносит [contents], а не выдумывает подделка: «12 упаковок, 2 просрочены» на пустом
 * хранилище было бы неправдой, которую тест принял бы за правду.
 */
class FakeMedKits(vararg kits: MedKit) : MedKitStorageRepository {

    private val stored = LinkedHashMap<Uuid, MedKit>()

    private val changes = MutableStateFlow(0)

    val medKits: List<MedKit> get() = stored.values.toList()

    /** Что лежит на полке — по тождеству полки; не названная пуста. */
    val contents = mutableMapOf<Uuid, MedKitContents>()

    init {
        kits.forEach { stored[it.id] = it }
    }

    override fun observeAll(today: LocalDate): Flow<List<MedKitProjection>> =
        changes.map { stored.values.map { it.seen() } }

    override fun observe(id: Uuid, today: LocalDate): Flow<MedKitProjection?> =
        changes.map { stored[id]?.seen() }

    override suspend fun find(id: Uuid): MedKit? = stored[id]

    override fun observeSyncedAt(id: Uuid): Flow<Instant?> = changes.map { null }

    override suspend fun add(medKit: MedKit) {
        stored[medKit.id] = medKit
        changes.value++
    }

    override suspend fun describe(medKitId: Uuid, name: String, location: String?): Boolean {
        val medKit = stored[medKitId] ?: return false
        stored[medKitId] = medKit.describe(name, location)
        changes.value++
        return true
    }

    override suspend fun delete(id: Uuid): Boolean = forget(id)

    override suspend fun mark(medKitId: Uuid, status: MedKitStatus): Boolean {
        val medKit = stored[medKitId] ?: return false
        stored[medKitId] = when (status) {
            MedKitStatus.PUBLISHING -> medKit.markPublishing()
            MedKitStatus.REMOVING -> medKit.markRemoving()
            MedKitStatus.ACTIVE -> error("пометку снимает закрытие команды, а не сценарий")
        }
        changes.value++
        return true
    }

    override suspend fun applyServerParticipants(id: Uuid, participantCount: Long, syncedAt: Instant) = Unit

    override suspend fun abandonServer(at: Instant): Int = 0

    /** «Полку убрали, пока экран был открыт». */
    fun forget(id: Uuid): Boolean {
        val removed = stored.remove(id) != null
        if (removed) changes.value++
        return removed
    }

    private fun MedKit.seen(): MedKitProjection = projection(contents[id] ?: MedKitContents.EMPTY)
}

/** Словарь, известный тестам: тот же снимок, что и у фикстур домена. */
class FakeVocabulary(
    private val snapshot: Vocabulary = VOCABULARY,
    private val units: List<QuantityUnit> = listOf(TABLETS, MILLILITRES),
    private val forms: List<DosageForm> = listOf(TABLET_FORM, CAPSULE_FORM)
) : VocabularyStorageRepository {

    override suspend fun snapshot(): Vocabulary = snapshot

    override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit

    override fun observeUnits(): Flow<List<QuantityUnit>> = MutableStateFlow(units)

    override fun observeForms(): Flow<List<DosageForm>> = MutableStateFlow(forms)
}

/**
 * Очередь в памяти. Проверкам представления от неё нужно одно: поставленные команды видно, и
 * поставлены они только тем полкам, которым есть что везти. Остальное — путь доставки: взятие в
 * отправку, исход, повтор — принадлежит очереди и проверяется на ней (`QueueOutboxTest` и
 * соседи), поэтому здесь эти двери честно падают, а не отвечают выдуманным.
 */
class FakeQueue : QueueStorage {

    /** Что уехало бы серверу: тест спрашивает хранилище, а не следит за вызовами. */
    val enqueued = mutableListOf<QueuedCommand>()

    override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant): SyncOperation {
        enqueued += queued
        return SyncOperation(
            id = queued.id,
            command = queued.command,
            sequence = enqueued.size.toLong(),
            createdAt = at,
            payloadVersion = 1
        )
    }

    override fun changes(): Flow<Unit> = MutableStateFlow(Unit)

    override suspend fun medKit(id: Uuid): MedKitRef? = null

    override suspend fun ready(now: Instant): List<StoredSyncOperation> = emptyList()

    override suspend fun nextDueAt(now: Instant): Instant? = null

    override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant): Take? =
        error("путь доставки проверяется на очереди, а не на экране")

    override suspend fun answered(id: Uuid, answer: RawResponse, at: Instant): Unit =
        error("путь доставки проверяется на очереди, а не на экране")

    override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant): Unit =
        error("путь доставки проверяется на очереди, а не на экране")

    override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant): Unit =
        error("путь доставки проверяется на очереди, а не на экране")
}

/**
 * «Одна транзакция» без базы: тело выполняется как есть. Подделка не обещает отката — что
 * половина записи невозможна, проверяется на настоящей базе (PLAN F5).
 */
object DirectTransactions : Transactions {

    override suspend fun <T> run(block: suspend () -> T): T = block()
}

/**
 * Лечение, следующее за коробкой, — заглушкой. Экраны локального учёта его не касаются: что курс
 * делает, когда коробка изменилась, проверяется на нём самом (`CourseFollowingTest`). Здесь
 * записано только то, за какими коробками следили: если экран забудет позвать сценарий, это
 * видно.
 */
class FakeFollowing : PackageFollowing {

    val followed = mutableListOf<Uuid>()

    override suspend fun follow(packageId: Uuid, at: Instant) {
        followed += packageId
    }

    override suspend fun lost(pkg: PackageRef, at: Instant) {
        followed += pkg.id
    }
}

/**
 * Лечение, которого нет. Экраны локального учёта курсов не касаются: коробку они заводят,
 * правят и убирают, а что при этом делает курс, проверяется на нём самом
 * (`CourseFollowingTest`, `PackageRelocationTest`). Подделка отвечает только на два вопроса
 * переноса — «кто держит коробку» и «кто на неё ссылается», — и на оба честно: никто.
 *
 * Остальные двери падают, а не отвечают выдуманным: сценарий, который сюда заглянул, пришёл не
 * за тем, чем занят экран, и молчаливый пустой ответ спрятал бы это от проверки.
 */
class FakeCourses : CourseStorageRepository {

    override suspend fun courseHolding(packageId: Uuid): Uuid? = null

    override suspend fun holdersOf(packageId: Uuid): List<Uuid> = emptyList()

    override fun observeDrafts(): Flow<List<CourseDraftProjection>> = noCourses()

    override fun observePlan(id: Uuid): Flow<CourseProjection?> = noCourses()

    override fun observeCoverage(id: Uuid): Flow<CourseCoverage?> = noCourses()

    override fun observeCoverages(): Flow<Map<Uuid, CourseCoverage>> = noCourses()

    override fun observeReductions(courseId: Uuid): Flow<List<CoverageReduction>> = noCourses()

    override suspend fun reductionsSince(courseId: Uuid, since: Instant): List<CoverageReduction> = noCourses()

    override suspend fun recentReductions(since: Instant): List<CoverageReduction> = noCourses()

    override suspend fun findDraft(id: Uuid): CourseDraft? = noCourses()

    override suspend fun findPlan(id: Uuid): Course? = noCourses()

    override suspend fun planIds(): List<Uuid> = noCourses()

    override suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean = noCourses()

    override suspend fun discardDraft(id: Uuid): Boolean = noCourses()

    override fun observeRecords(): Flow<List<CourseRecordProjection>> = noCourses()

    override fun observeRecord(id: Uuid): Flow<CourseRecordProjection?> = noCourses()

    override suspend fun findRecord(id: Uuid): CourseRecord? = noCourses()

    override suspend fun rename(id: Uuid, title: String, note: String?): Boolean = noCourses()

    override suspend fun planInProgress(id: Uuid): CourseInProgress? = noCourses()

    override suspend fun recordReduction(reduction: CoverageReduction): Unit = noCourses()

    override suspend fun amend(course: Course, expected: Revision): Boolean = noCourses()

    override suspend fun reallocate(reallocation: CourseReallocation): Boolean = noCourses()

    override suspend fun updateSources(course: Course, expected: Revision): Boolean = noCourses()

    override suspend fun activate(activation: CourseDraft.Activation, planned: List<CourseIntake>): Unit = noCourses()

    override suspend fun close(closing: CourseCompletion.Closing): Unit = noCourses()

    private fun noCourses(): Nothing = error("курсы проверяются на себе, а не на экране учёта")
}
