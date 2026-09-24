package com.kert0n.medapp.feature.packages

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.closing
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.operation.syncState
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Человек переставил коробку на другую полку (PLAN E6): та же коробка, курс её не теряет, следа
 * в истории нет (D7). Что ещё нужно серверу, решает граница публикации — четыре случая.
 */
@RunWith(AndroidJUnit4::class)
class PackageRelocationTest {

    private lateinit var database: MedAppDatabase
    private lateinit var relocation: PackageRelocation

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        relocation = Scenarios(database, LATER).packageRelocation
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun publish(vararg kits: Uuid) {
        for (kit in kits) {
            database.medKits().upsert(
                medKit(id = kit, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
            )
        }
    }

    /** Полка, о которой принято решение: публикация уже назвала серверу своё содержимое. */
    private suspend fun publishing(kit: Uuid) {
        assertTrue(database.medKitRepository().mark(kit, MedKitStatus.PUBLISHING))
    }

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    private suspend fun assertMovedAndStillASource() {
        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(listOf(PACK), database.courses().sourcePackagesOf(COURSE))
    }

    @Test
    fun localToLocalChangesOnlyThePlace() = runTest {
        assertEquals(PackageRelocation.Outcome.MOVED, relocation.move(PACK, SHARED_KIT))

        assertMovedAndStillASource()
        assertEquals(emptyList<SyncCommand>(), commands())
    }

    /**
     * Общая коробка ждёт ответа сервера на прежней полке: отказ по версии иначе оставил бы её на
     * чужой. Новое место придёт снимком ответа — он и есть истина по этой коробке (PLAN E1, E6).
     */
    @Test
    fun sharedToSharedTellsTheServerToMoveAndWaits() = runTest {
        publish(HOME_KIT, SHARED_KIT)

        assertEquals(PackageRelocation.Outcome.MARKED, relocation.move(PACK, SHARED_KIT))

        assertEquals(HOME_KIT, requireNotNull(database.packageRepository().find(PACK)).medKit.id)
        assertEquals(listOf(PACK), database.courses().sourcePackagesOf(COURSE))
        assertEquals(listOf(PackageSyncCommand.Move(PACK, SHARED_KIT)), commands())
        assertEquals(PackageStatus.CHANGING, requireNotNull(database.packageRepository().find(PACK)).status)
    }

    /** Сервер ответил на последнюю команду коробки — пометка снята, коробка обычная (PLAN E1). */
    @Test
    fun theLastAnswerReleasesTheChangingPackage() = runTest {
        publish(HOME_KIT, SHARED_KIT)
        relocation.move(PACK, SHARED_KIT)

        val operation = database.syncOperations().all().single().operation.id
        database.queueStorage().settle(
            operation,
            Delivery.Applied(PackageState.None).settlement(PackageSyncCommand.Move(PACK, SHARED_KIT)),
            LATER
        )

        assertEquals(PackageStatus.ACTIVE, requireNotNull(database.packageRepository().find(PACK)).status)
    }

    /** Местная коробка на общей полке — публикация коробки, а выделение курса едет следом бронью. */
    @Test
    fun localToSharedPublishesThePackageWithItsClaim() = runTest {
        publish(SHARED_KIT)

        assertEquals(PackageRelocation.Outcome.MOVED, relocation.move(PACK, SHARED_KIT))

        assertMovedAndStillASource()
        val queued = commands()
        val create = queued[0] as PackageSyncCommand.Create
        assertEquals(PACK, create.packageId)
        assertEquals(SHARED_KIT, create.medKitId)
        // Числа команда не носит: его прочитают у коробки при взятии — тем, каким оно станет к
        // отправке, а не каким было в миг решения (PLAN E6).
        assertEquals(HOME_KIT, create.fromMedKitId)
        val claim = queued[1] as PackageSyncCommand.SetClaim
        assertEquals(tablets("10"), claim.amount)
        assertEquals(2, queued.size)
        assertEquals(PackageStatus.CHANGING, requireNotNull(database.packageRepository().find(PACK)).status)
        // Обвязки у только что опубликованной коробки ещё нет: первое подтверждённое число даст ответ.
        assertNull(requireNotNull(database.packages().find(PACK)).pack.syncState().version)
    }

    /** Без выделения бронь не ставится: нулевая бронь — это снятие, а снимать нечего. */
    @Test
    fun localToSharedWithoutACourseSendsOnlyTheCreation() = runTest {
        publish(SHARED_KIT)
        database.courseRepository().close(
            closing(
                requireNotNull(database.courseRepository().findRecord(COURSE))
                    .close(com.kert0n.medapp.domain.course.CourseRecord.Outcome.CANCELLED, LATER)
            )
        )

        relocation.move(PACK, SHARED_KIT)

        assertEquals(1, commands().filterIsInstance<PackageSyncCommand.Create>().size)
        assertEquals(1, commands().size)
    }

    /**
     * «Унёс домой»: коробка сразу на моей полке и помечена, а серверу — снять её с общей. Публиковать
     * мою полку незачем (PLAN E6).
     */
    @Test
    fun sharedToLocalIsCarriedHomeAtOnce() = runTest {
        publish(HOME_KIT)

        assertEquals(PackageRelocation.Outcome.MOVED, relocation.move(PACK, SHARED_KIT))

        assertMovedAndStillASource()
        assertEquals(listOf(PackageSyncCommand.Withdraw(PACK, HOME_KIT, tablets("20"))), commands())
        assertEquals(PackageStatus.CHANGING, requireNotNull(database.packageRepository().find(PACK)).status)
    }

    /** Сервер коробку забыл: у меня она просто местная — без версий и пометки. */
    @Test
    fun theServerForgettingTheBoxLeavesItPlainlyLocal() = runTest {
        publish(HOME_KIT)
        relocation.move(PACK, SHARED_KIT)

        theServerAnswers(Delivery.Applied(PackageState.Gone))

        val row = requireNotNull(database.packages().find(PACK))
        assertEquals(SHARED_KIT, row.pack.medKitId)
        assertNull(row.pack.syncState().version)
        assertEquals(PackageStatus.ACTIVE, row.toDomain(VOCABULARY).status)
        assertEquals(listOf(PACK), database.courses().sourcePackagesOf(COURSE))
    }

    /** Сервер отказал: коробка возвращается на полку, откуда её взяли, и человек решает заново (E1). */
    @Test
    fun aRefusalReturnsTheBoxToTheSharedShelf() = runTest {
        publish(HOME_KIT)
        relocation.move(PACK, SHARED_KIT)

        theServerAnswers(Delivery.Refused(RefusalReason.STALE, PackageState.None))

        val pkg = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(HOME_KIT, pkg.medKit.id)
        assertEquals(PackageStatus.ACTIVE, pkg.status)
    }

    /**
     * Коробку переложили на общую полку, а полки не стало, пока команда ждала связи. Сама коробка
     * при этом никуда не делась — она у человека в руках, — поэтому она возвращается на ту полку, с
     * которой её принесли, а бронь уходит каскадом за своим созданием (PLAN E6).
     */
    @Test
    fun aBoxWhoseSharedShelfIsGoneComesHomeInsteadOfEnding() = runTest {
        publish(SHARED_KIT)
        relocation.move(PACK, SHARED_KIT)

        val create = database.syncOperations().all()
            .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation }
            .single { it.command is PackageSyncCommand.Create }
        database.queueStorage().settle(
            create.id,
            Delivery.Refused(RefusalReason.STALE, PackageState.None).settlement(create.command),
            LATER
        )

        val pkg = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(HOME_KIT, pkg.medKit.id)
        assertEquals(PackageStatus.ACTIVE, pkg.status)
        assertEquals(listOf(PACK), database.courses().sourcePackagesOf(COURSE))
        assertEquals(
            emptyList<SyncOperationStatus>(),
            database.syncOperations().all()
                .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.status }
                .filter { !it.isClosed }
        )
    }

    private suspend fun theServerAnswers(delivery: Delivery) {
        val stored = database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        database.queueStorage().settle(stored.operation.id, delivery.settlement(stored.operation.command), LATER)
    }

    /**
     * Полка, о которой уже принято решение, новых коробок не берёт: она вот-вот уйдёт, и коробка
     * ушла бы с ней, ничего человеку не сказав (PLAN E1, E6).
     */
    @Test
    fun aShelfBeingRemovedTakesNoBoxes() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, name = "Дача", status = MedKitStatus.REMOVING).toMedKitStorageEntity()
        )

        assertEquals(PackageRelocation.Outcome.TARGET_BUSY, relocation.move(PACK, SHARED_KIT))

        assertEquals(HOME_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(emptyList<SyncCommand>(), commands())
    }

    @Test
    fun aMissingTargetOrTheSameShelfChangesNothing() = runTest {
        assertEquals(PackageRelocation.Outcome.TARGET_GONE, relocation.move(PACK, Uuid.random()))
        assertEquals(PackageRelocation.Outcome.TARGET_IS_THE_SAME, relocation.move(PACK, HOME_KIT))
        assertEquals(PackageRelocation.Outcome.GONE, relocation.move(Uuid.random(), SHARED_KIT))
        assertEquals(HOME_KIT, database.packageRepository().find(PACK)?.medKit?.id)
    }

    /**
     * Решение о полке не обходят переносом. Из публикуемой полки коробку не уносят: её создание
     * уже стоит в очереди, и коробка оказалась бы у сервера мимо своей двери либо не оказалась бы
     * вовсе. В публикуемую не кладут: серверу о её содержимом уже сказано, и эта коробка в
     * сказанное не вошла (PLAN E5, решение владельца 2026-09-13).
     */
    @Test
    fun aShelfWaitingForItsOwnAnswerIsNotWalkedAroundByAMove() = runTest {
        publish(SHARED_KIT)
        publishing(HOME_KIT)

        assertEquals(PackageRelocation.Outcome.ORIGIN_BUSY, relocation.move(PACK, SHARED_KIT))

        assertEquals(HOME_KIT, requireNotNull(database.packageRepository().find(PACK)).medKit.id)
        assertTrue(commands().isEmpty())
    }

    @Test
    fun aShelfWaitingForItsOwnAnswerTakesNoNewBoxes() = runTest {
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("5"), form = TABLET_FORM))
        publishing(SHARED_KIT)

        assertEquals(PackageRelocation.Outcome.TARGET_BUSY, relocation.move(OTHER_PACK, SHARED_KIT))

        assertEquals(HOME_KIT, requireNotNull(database.packageRepository().find(OTHER_PACK)).medKit.id)
        assertTrue(commands().isEmpty())
    }
}
