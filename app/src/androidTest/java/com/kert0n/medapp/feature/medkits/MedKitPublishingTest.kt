package com.kert0n.medapp.feature.medkits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
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
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Человек делает свою полку общей (ТЗ 4.1.1.11, PLAN E5). На настоящей базе: решение случается
 * целиком и сразу, а серверу его везёт очередь — полка своей командой, коробки обычными командами
 * пачек следом. Половины полки не бывает: приглашения появляются, только когда уехало всё.
 */
@RunWith(AndroidJUnit4::class)
class MedKitPublishingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var publishing: MedKitPublishing
    private lateinit var removal: MedKitRemoval

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        val scenarios = Scenarios(database, LATER)
        publishing = scenarios.medKitPublishing
        removal = scenarios.medKitRemoval
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("1")))
        // Курс держит PACK: его выделение должно уехать бронью следом за созданием коробки.
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun operations(): List<SyncOperation> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation }

    private suspend fun commands(): List<SyncCommand> = operations().map { it.command }

    private suspend fun shelf(): MedKit = requireNotNull(database.medKits().find(HOME_KIT)).toDomain()

    /** Сервер ответил на операцию [id] — тем же путём, что и в бою: эффектами её закрытия. */
    private suspend fun serverAnswers(id: Uuid, delivery: Delivery) {
        val stored = requireNotNull(database.syncOperations().find(id)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        database.queueStorage().settle(id, delivery.settlement(stored.operation.command), LATER)
    }

    private suspend fun theShelfIsCreated() {
        serverAnswers(operations().first { it.command is MedKitSyncCommand.Publish }.id, Delivery.Applied(PackageState.None))
    }

    /**
     * Решение целиком: полка помечена, серверу поставлены её команда и по команде на каждую
     * коробку — зависимые от неё, — а выделение курса едет бронью следом за своей коробкой.
     */
    @Test
    fun theDecisionMarksTheShelfAndQueuesItWithItsContents() = runTest {
        val outcome = publishing.publish(HOME_KIT)

        assertEquals(MedKitPublishing.Outcome.PUBLISHING, outcome)
        assertEquals(MedKitStatus.PUBLISHING, shelf().status)
        assertEquals(MedKit.Publication.LOCAL, shelf().publication)
        assertEquals(PackageStatus.CHANGING, requireNotNull(database.packageRepository().find(PACK)).status)
        assertEquals(PackageStatus.CHANGING, requireNotNull(database.packageRepository().find(OTHER_PACK)).status)

        val queued = operations()
        val publish = queued.first()
        assertEquals(MedKitSyncCommand.Publish(HOME_KIT), publish.command)
        assertEquals(
            setOf(PACK, OTHER_PACK),
            queued.map { it.command }.filterIsInstance<PackageSyncCommand.Create>().mapTo(HashSet()) { it.packageId }
        )
        // Коробку некуда класть, пока полки у сервера нет.
        for (create in queued.filter { it.command is PackageSyncCommand.Create }) {
            assertEquals(setOf(publish.id), create.dependsOn)
        }
        val claim = queued.single { it.command is PackageSyncCommand.SetClaim }
        assertEquals(PACK, (claim.command as PackageSyncCommand.SetClaim).packageId)
        assertEquals(4, queued.size)
    }

    /**
     * Полка, которая к серверу только едет, серверу ещё не отвечает: его там нет, спорить не с кем,
     * и содержимое учитывается местно. Команды её при этом доставляются — ими она и станет
     * известна, — а приглашений она не выдаёт ни до ответа, ни после: коробки в пути, и
     * приглашённый увидел бы половину полки (PLAN D2, E5).
     */
    @Test
    fun aShelfOnItsWayIsStillLocalDeliversItsCommandsAndInvitesNobody() = runTest {
        publishing.publish(HOME_KIT)
        assertFalse(shelf().answersToServer)
        assertTrue(shelf().acceptsCommands)
        assertFalse(shelf().acceptsInvitations)

        theShelfIsCreated()

        assertEquals(MedKit.Publication.PUBLISHED, shelf().publication)
        assertEquals(MedKitStatus.PUBLISHING, shelf().status)
        // Полка у сервера есть: с этого мига её содержимое отвечает ему.
        assertTrue(shelf().answersToServer)
        assertFalse(shelf().acceptsInvitations)
    }

    /** Последняя закрытая команда содержимого доводит решение: пометки нет, приглашения есть. */
    @Test
    fun theLastAnswerAboutTheContentsFinishesTheDecision() = runTest {
        publishing.publish(HOME_KIT)
        theShelfIsCreated()

        for (operation in operations().filter { it.command !is MedKitSyncCommand.Publish }) {
            serverAnswers(operation.id, Delivery.Applied(PackageState.None))
        }

        assertEquals(MedKitStatus.ACTIVE, shelf().status)
        assertTrue(shelf().acceptsInvitations)
        assertEquals(PackageStatus.ACTIVE, requireNotNull(database.packageRepository().find(PACK)).status)
        assertEquals(PackageStatus.ACTIVE, requireNotNull(database.packageRepository().find(OTHER_PACK)).status)
    }

    /**
     * Сервер полку не принял: она остаётся местной, команды её содержимого закрываются каскадом, а
     * пометка снимается — человек решает заново. Откатывать нечего: на сервере ничего нашего не
     * появилось (PLAN E5).
     */
    @Test
    fun aRefusedShelfStaysLocalAndTakesItsContentsWithIt() = runTest {
        publishing.publish(HOME_KIT)

        serverAnswers(
            operations().first { it.command is MedKitSyncCommand.Publish }.id,
            Delivery.Refused(RefusalReason.INVALID, PackageState.None)
        )

        assertEquals(MedKit.Publication.LOCAL, shelf().publication)
        assertEquals(MedKitStatus.ACTIVE, shelf().status)
        assertEquals(PackageStatus.ACTIVE, requireNotNull(database.packageRepository().find(PACK)).status)
        assertEquals(
            emptyList<SyncOperationStatus>(),
            operations().map { it.status }.filter { !it.isClosed }
        )
    }

    /**
     * Сделанное, пока полка едет к серверу, учитывается местно — и отказ его не отменяет: сервер о
     * полке не слышал, спорить не с кем, и число у человека верное (PLAN E5, решение владельца
     * 2026-09-13).
     *
     * Красная проверка: пока публикуемая полка считалась отвечающей серверу, расход уезжал командой,
     * получал 404 «такой коробки нет» и кончал коробку утратой доступа — уничтожал вещь, которую
     * человек держит в руках.
     */
    @Test
    fun whatWasDoneWhileTheShelfWasOnItsWayIsLocalAndSurvivesTheRefusal() = runTest {
        publishing.publish(HOME_KIT)

        val recorded = Scenarios(database, LATER).unplannedIntakeRecording.record(PACK, dose("1"), LATER)

        // Расход списан у себя, и везти его некуда: коробки у сервера нет.
        assertEquals(IntakeAccounting.LOCAL_APPLIED, (recorded as UnplannedIntakeRecording.Outcome.Recorded).accounting)
        assertEquals(tablets("19"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertFalse(commands().any { it is PackageSyncCommand.Consume })

        serverAnswers(
            operations().first { it.command is MedKitSyncCommand.Publish }.id,
            Delivery.Refused(RefusalReason.INVALID, PackageState.None)
        )

        val kept = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(tablets("19"), kept.quantity)
        assertEquals(PackageStatus.ACTIVE, kept.status)
        assertEquals(MedKit.Publication.LOCAL, shelf().publication)
    }

    /**
     * Успешная публикация везёт серверу то, что в коробке есть **сейчас**, а не то, что было в миг
     * решения: тело создания собирается по прочитанной коробке при взятии (PLAN E1, E6).
     */
    @Test
    fun theCreationCarriesTheNumberTheBoxHasWhenItLeaves() = runTest {
        publishing.publish(HOME_KIT)
        Scenarios(database, LATER).unplannedIntakeRecording.record(PACK, dose("1"), LATER)
        theShelfIsCreated()

        val create = operations().first { (it.command as? PackageSyncCommand.Create)?.packageId == PACK }
        val taken = database.queueStorage().take(create.id, null, LATER) as com.kert0n.medapp.queue.Take.Sending

        val body = requireNotNull(taken.operation.prepared?.body)
        assertTrue(body.contains("\"quantity\":\"19\""))
    }

    /** Полка уже общая — публиковать нечего; решение о ней уже принято — ждём его ответа. */
    @Test
    fun aShelfIsNotPublishedTwiceAndNotWhileItIsBusy() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )

        assertEquals(MedKitPublishing.Outcome.ALREADY_SHARED, publishing.publish(SHARED_KIT))
        assertEquals(MedKitPublishing.Outcome.MED_KIT_GONE, publishing.publish(Uuid.random()))

        publishing.publish(HOME_KIT)
        assertEquals(MedKitPublishing.Outcome.BUSY, publishing.publish(HOME_KIT))
    }

    /** Убрать полку посреди публикации нельзя: о ней уже принято решение, и ждут его ответа. */
    @Test
    fun aShelfOnItsWayIsNotRemoved() = runTest {
        publishing.publish(HOME_KIT)

        assertEquals(MedKitRemoval.Outcome.BUSY, removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway))

        assertNotNull(database.medKits().find(HOME_KIT))
        assertEquals(MedKitStatus.PUBLISHING, shelf().status)
        assertFalse(commands().any { it is MedKitSyncCommand.Delete })
    }
}
