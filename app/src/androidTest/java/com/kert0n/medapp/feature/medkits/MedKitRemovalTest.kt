package com.kert0n.medapp.feature.medkits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
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
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.operation.toStorageEntity as toIntakeStorageEntity
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Человек убирает полку (ТЗ 4.1.1.2.3) — по коробкам, через домен, одной транзакцией. На
 * настоящей базе: то, ради чего всё это затевалось, — схема даёт убрать аптечку, а история
 * лечения это переживает (PLAN E6, D3, D6). Шесть случаев — по границе публикации источника и
 * цели: серверу об общей полке говорит одна команда аптечки, о местной коробке на общей полке —
 * её публикация, а общее содержимое на местную полку уносится домой по коробкам.
 */
@RunWith(AndroidJUnit4::class)
class MedKitRemovalTest {

    private lateinit var database: MedAppDatabase
    private lateinit var removal: MedKitRemoval


    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        removal = Scenarios(database, LATER).medKitRemoval
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("1")))
        // Курс держит PACK источником: разбор полки решает и его судьбу.
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
        database.intakes().upsert(
            plannedIntake().confirm(pack(id = PACK).take(dose("2"), LATER).getOrThrow())
                .toIntakeStorageEntity()
        )
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

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    private suspend fun sourcesOfCourse(): List<Uuid> = database.courses().sourcePackagesOf(COURSE)

    /**
     * Сервер согласился. Исход доводит до конца очередь — тем же путём, что и в бою: эффект
     * закрытия операции, а не отдельная дверь для теста.
     */
    private suspend fun theServerAgrees() {
        val stored = database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        database.queueStorage().settle(
            stored.operation.id,
            Delivery.Applied(PackageState.None).settlement(stored.operation.command),
            LATER
        )
    }


    /** Ответ сервера по команде самой полки, когда в очереди есть и команды её коробок. */
    private suspend fun theServerAgreesAboutTheShelf() {
        val stored = database.syncOperations().all()
            .map { it.toDomain(VOCABULARY) as StoredSyncOperation.Readable }
            .single { it.operation.command is MedKitSyncCommand }
        database.queueStorage().settle(
            stored.operation.id,
            Delivery.Applied(PackageState.None).settlement(stored.operation.command),
            LATER
        )
    }

    /**
     * Коробка ждёт ответа по своему удалению, а полку в это время переставляют на другую общую.
     * Едет она **вместе с полкой** — не по своему решению, — поэтому пометка переезду не мешает, и
     * ответ полки не роняет применение исхода (PLAN E1, E6).
     *
     * Красная проверка: переставлять её человеческим `moveTo` — помеченная коробка отвергает
     * переход, и ответ полки падает исключением посреди транзакции.
     */
    @Test
    fun theShelfAnswerCarriesEvenABoxThatWaitsForItsOwn() = runTest {
        publish(HOME_KIT, SHARED_KIT)
        Scenarios(database, LATER).packageRemoval.remove(PACK)
        assertEquals(PackageStatus.REMOVING, database.packageRepository().find(PACK)?.status)

        assertEquals(MedKitRemoval.Outcome.MARKED, removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT)))
        theServerAgreesAboutTheShelf()

        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        // Своё решение коробки живо: его отпустит ответ по её собственной команде.
        assertEquals(PackageStatus.REMOVING, database.packageRepository().find(PACK)?.status)
        assertNull(database.medKits().find(HOME_KIT))
    }

    /**
     * Унести домой полку, коробка которой ждёт своего ответа, нельзя: унести её нечем, а полка
     * ушла бы у всех и забрала коробку с собой. Ждём ответа по коробке (PLAN E6).
     */
    @Test
    fun aShelfIsNotCarriedHomeWhileOneOfItsBoxesWaits() = runTest {
        publish(HOME_KIT)
        Scenarios(database, LATER).packageRemoval.remove(PACK)

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.CONTENTS_BUSY, outcome)
        assertEquals(MedKitStatus.ACTIVE, database.medKits().find(HOME_KIT)?.toDomain()?.status)
        assertEquals(HOME_KIT, database.packageRepository().find(OTHER_PACK)?.medKit?.id)
    }

    /**
     * В полку, которую уже убирают, не переносят: она вот-вот уйдёт, и коробки ушли бы с ней,
     * ничего человеку не сказав (PLAN E1, E6).
     */
    @Test
    fun aShelfBeingRemovedTakesNoNewBoxes() = runTest {
        publish(SHARED_KIT)
        assertEquals(MedKitRemoval.Outcome.MARKED, removal.remove(SHARED_KIT, MedKitRemoval.Fate.ThrowAway))

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.TARGET_BUSY, outcome)
        assertNotNull(database.medKits().find(HOME_KIT))
        assertEquals(HOME_KIT, database.packageRepository().find(PACK)?.medKit?.id)
    }

    /** «Забрал аптечку домой», обе местные: содержимое переезжает целиком, курс коробку не теряет. */
    @Test
    fun takingALocalMedKitAwayIntoALocalOneMovesEverythingAndKeepsTheCourse() = runTest {
        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.REMOVED, outcome)
        assertNull(database.medKits().find(HOME_KIT))
        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(SHARED_KIT, database.packageRepository().find(OTHER_PACK)?.medKit?.id)
        assertEquals(listOf(PACK), sourcesOfCourse())
        assertEquals(emptyList<SyncCommand>(), commands())
    }

    /**
     * «Выбросил вместе с лекарствами» (ТЗ 4.1.1.2.3.1): коробок не остаётся, курс теряет источник
     * своим переходом, а история — запись эпизода и приём — переживает: она держится за записи о
     * коробках (PLAN D3, D6).
     *
     * Красная проверка: посадить ключ приёма на живую строку — аптечку, из которой хоть раз
     * принимали, выбросить станет нельзя, и случай краснеет.
     */
    @Test
    fun throwingTheMedKitOutWithItsDrugsKeepsTheTreatment() = runTest {
        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway)

        assertEquals(MedKitRemoval.Outcome.REMOVED, outcome)
        assertNull(database.medKits().find(HOME_KIT))
        assertNull(database.packageRepository().find(PACK))
        assertNull(database.packageRepository().find(OTHER_PACK))
        assertEquals(emptyList<Uuid>(), sourcesOfCourse())

        assertNotNull(database.courses().findRecord(COURSE))
        val intake = requireNotNull(database.intakes().find(INTAKE)).toDomain(VOCABULARY)
        assertEquals(dose("2"), intake.taken?.amount)
        assertEquals("Парацетамол", intake.taken?.pkg?.name)
    }

    /**
     * Местные коробки на общую полку: каждая публикуется в целевой аптечке, а выделение курса
     * едет следом бронью — на сервере оно иначе не появилось бы (PLAN E6).
     */
    @Test
    fun takingALocalMedKitAwayIntoASharedOnePublishesEachPackage() = runTest {
        publish(SHARED_KIT)

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.REMOVED, outcome)
        assertNull(database.medKits().find(HOME_KIT))
        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(listOf(PACK), sourcesOfCourse())
        val queued = commands()
        val creates = queued.filterIsInstance<PackageSyncCommand.Create>()
        assertEquals(setOf(PACK, OTHER_PACK), creates.mapTo(HashSet()) { it.packageId })
        assertEquals(SHARED_KIT, creates.first().medKitId)
        val claim = queued.filterIsInstance<PackageSyncCommand.SetClaim>().single()
        assertEquals(PACK, claim.packageId)
        assertEquals(tablets("10"), claim.amount)
        assertEquals(3, queued.size)
    }

    /**
     * Общая полка на общую: сервер переставляет всё сам одной командой аптечки и решает судьбу
     * броней; локально коробки только меняют место, курс их не теряет.
     */
    /**
     * Общая полка на общую: серверу — одна команда, и до его ответа **ничего не трогается**.
     * Полка на месте, коробки на месте, курс держит источник (PLAN E1, E6).
     */
    @Test
    fun takingASharedMedKitAwayIntoASharedOneIsOneCommandAndAWait() = runTest {
        publish(HOME_KIT, SHARED_KIT)

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.MARKED, outcome)
        assertNotNull(database.medKits().find(HOME_KIT))
        assertEquals(HOME_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(listOf(PACK), sourcesOfCourse())
        assertEquals(listOf(MedKitSyncCommand.Delete(HOME_KIT, transferTo = SHARED_KIT)), commands())
        // Решение видно на вещах: полка убирается, коробки переставляются, и пользоваться ими можно.
        assertEquals(MedKitStatus.REMOVING, database.medKits().find(HOME_KIT)?.toDomain()?.status)
        assertEquals(PackageStatus.CHANGING, database.packageRepository().find(PACK)?.status)
        assertEquals(PackageStatus.CHANGING, database.packageRepository().find(OTHER_PACK)?.status)
    }

    /** Сервер согласился — тогда и переезжает содержимое, и уходит сама полка. */
    @Test
    fun theServerAgreeingMovesTheContentsAndTakesTheShelf() = runTest {
        publish(HOME_KIT, SHARED_KIT)
        removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        theServerAgrees()

        assertNull(database.medKits().find(HOME_KIT))
        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(SHARED_KIT, database.packageRepository().find(OTHER_PACK)?.medKit?.id)
        assertEquals(listOf(PACK), sourcesOfCourse())
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(PACK)?.status)
    }

    /**
     * Сервер отказал: пометки сняты и с полки, и с коробок, которые она пометила, — ничего не
     * тронуто, и человек решает заново (PLAN E1, E6).
     */
    @Test
    fun theServerRefusingReturnsTheShelfAndItsPackagesToUse() = runTest {
        publish(HOME_KIT)
        removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway)

        val stored = database.syncOperations().all().single().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        database.queueStorage().settle(
            stored.operation.id,
            Delivery.Refused(RefusalReason.INVALID, PackageState.None).settlement(stored.operation.command),
            LATER
        )

        assertEquals(MedKitStatus.ACTIVE, database.medKits().find(HOME_KIT)?.toDomain()?.status)
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(PACK)?.status)
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(OTHER_PACK)?.status)
        assertEquals(MedKitRemoval.Outcome.MARKED, removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway))
    }

    /** Полку, которая уже ждёт ответа, не убирают второй раз. */
    @Test
    fun aShelfWaitingForAnAnswerIsNotRemovedAgain() = runTest {
        publish(HOME_KIT)
        removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway)

        assertEquals(MedKitRemoval.Outcome.BUSY, removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway))
        assertEquals(1, commands().size)
    }

    /**
     * Общую полку выбросили: серверу — одна команда, а до его ответа коробки целы. Иначе отказ
     * уничтожил бы у нас то, что у других участников живо, и вернуть это было бы нечем (PLAN E6).
     */
    @Test
    fun throwingASharedMedKitOutIsOneCommandAndAWait() = runTest {
        publish(HOME_KIT)

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway)

        assertEquals(MedKitRemoval.Outcome.MARKED, outcome)
        assertNotNull(database.medKits().find(HOME_KIT))
        assertNotNull(database.packageRepository().find(PACK))
        assertEquals(listOf(PACK), sourcesOfCourse())
        assertEquals(listOf(MedKitSyncCommand.Delete(HOME_KIT)), commands())
        assertEquals(PackageStatus.REMOVING, database.packageRepository().find(PACK)?.status)
    }

    /** Сервер согласился — коробок не остаётся, лечение цело. */
    @Test
    fun theServerAgreeingThrowsTheSharedShelfOutWithItsDrugs() = runTest {
        publish(HOME_KIT)
        removal.remove(HOME_KIT, MedKitRemoval.Fate.ThrowAway)

        theServerAgrees()

        assertNull(database.medKits().find(HOME_KIT))
        assertNull(database.packageRepository().find(PACK))
        assertEquals(emptyList<Uuid>(), sourcesOfCourse())
        assertNotNull(database.courses().findRecord(COURSE))
        assertEquals("Парацетамол", requireNotNull(database.intakes().find(INTAKE)).toDomain(VOCABULARY).taken?.pkg?.name)
    }

    /**
     * Общую полку забрали домой: каждая коробка сразу на моей полке, серверу — унести её, и полка
     * уходит у всех следом, завися от них (PLAN E6).
     */
    @Test
    fun takingASharedMedKitHomeCarriesEveryBoxAndThenRemovesTheShelf() = runTest {
        publish(HOME_KIT)

        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))

        assertEquals(MedKitRemoval.Outcome.MARKED, outcome)
        assertEquals(SHARED_KIT, database.packageRepository().find(PACK)?.medKit?.id)
        assertEquals(SHARED_KIT, database.packageRepository().find(OTHER_PACK)?.medKit?.id)
        assertEquals(PackageStatus.CHANGING, database.packageRepository().find(PACK)?.status)
        assertEquals(MedKitStatus.REMOVING, database.medKits().find(HOME_KIT)?.toDomain()?.status)
        assertEquals(listOf(PACK), sourcesOfCourse())
        val queued = commands()
        assertEquals(
            setOf(PackageSyncCommand.Withdraw(PACK, HOME_KIT, tablets("20")), PackageSyncCommand.Withdraw(OTHER_PACK, HOME_KIT, tablets("1"))),
            queued.filterIsInstance<PackageSyncCommand.Withdraw>().toSet()
        )
        assertEquals(MedKitSyncCommand.Delete(HOME_KIT), queued.last())
        assertEquals(3, queued.size)
    }

    /**
     * Одну коробку унести не вышло: она возвращается на полку, а полка остаётся — иначе сервер
     * выбросил бы коробку вместе с ней (PLAN E1, E6).
     */
    @Test
    fun aBoxThatCouldNotBeCarriedHomeKeepsTheShelf() = runTest {
        publish(HOME_KIT)
        removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(SHARED_KIT))
        val rows = database.syncOperations().all()
        val refused = rows.first().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        val box = (refused.operation.command as PackageSyncCommand.Withdraw).packageId

        database.queueStorage().settle(
            refused.operation.id,
            Delivery.Refused(RefusalReason.STALE, PackageState.None).settlement(refused.operation.command),
            LATER
        )

        assertEquals(HOME_KIT, database.packageRepository().find(box)?.medKit?.id)
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(box)?.status)
        val delete = database.syncOperations().all().last().toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(com.kert0n.medapp.queue.SyncOperationStatus.REFUSED, delete.operation.status)
        assertEquals(MedKitStatus.ACTIVE, database.medKits().find(HOME_KIT)?.toDomain()?.status)
    }

    /** Целевую аптечку удалили, пока человек выбирал: не записано ничего, и сказано почему. */
    @Test
    fun aTargetThatIsGoneChangesNothing() = runTest {
        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(Uuid.random()))

        assertEquals(MedKitRemoval.Outcome.TARGET_GONE, outcome)
        assertNotNull(database.medKits().find(HOME_KIT))
        assertEquals(HOME_KIT, database.packageRepository().find(PACK)?.medKit?.id)
    }

    /** Переносить в саму себя нечего: содержимое и так там. */
    @Test
    fun aMedKitIsNotItsOwnTarget() = runTest {
        val outcome = removal.remove(HOME_KIT, MedKitRemoval.Fate.MoveTo(HOME_KIT))

        assertEquals(MedKitRemoval.Outcome.TARGET_IS_THE_SAME, outcome)
        assertNotNull(database.medKits().find(HOME_KIT))
    }

    @Test
    fun aMedKitThatIsAlreadyGoneSaysSo() = runTest {
        assertEquals(MedKitRemoval.Outcome.MED_KIT_GONE, removal.remove(Uuid.random(), MedKitRemoval.Fate.ThrowAway))
    }
}
