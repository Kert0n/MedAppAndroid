package com.kert0n.medapp.storage.server

import org.junit.Assert.assertNotNull
import java.math.BigDecimal
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.INTAKE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.fixture.FakeServer
import com.kert0n.medapp.fixture.FakeSyncSchedule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Строку, которую приложение прочитать не может, ожиданием не доставить: решить её может только
 * человек, и экран состояния синхронизации ему об этом говорит. Будить ради неё устройство
 * планировщиком незачем — заход за остатком её не прочитает и в следующий раз.
 */
@RunWith(AndroidJUnit4::class)
class UnreadableBacklogTest {

    private lateinit var database: MedAppDatabase
    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    private val row: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000093")

    @Before
    fun openDatabase() = runBlocking {
        database = inMemoryDatabase()
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
        database.packageRepository().add(
            pack(quantity = tablets("20"), form = TABLET_FORM, medKit = medKit(publication = MedKit.Publication.PUBLISHED).ref),
            PackageSyncState(PACK, ResourceVersion(3), ResourceVersion(1), at)
        )
    }

    @After
    fun closeDatabase() = database.close()

    /**
     * В очереди одна строка — чужой версии формата после обновления. Заход её пропускает, и
     * планировщику приходить за ней не велено: иначе WorkManager поднимал бы процесс с растущей
     * задержкой без конца, пока человек не разберёт строку.
     *
     * Красная проверка: остаток очереди считался запросом по всем незакрытым строкам, и
     * нечитаемая строка в нём была — заход за остатком ставился каждым заходом.
     */
    @Test
    fun anUnreadableRowAloneAsksTheSchedulerForNothing() = runBlocking {
        val stored = database.syncOperations().enqueue(row, PackageSyncCommand.Delete(PACK), at).toStorageEntity()
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId
            )
        )
        val schedule = FakeSyncSchedule()

        val round = FakeServer().synchronization(database, clock, schedule).synchronize()

        assertEquals(listOf(row), round.queue.skipped.map { it.id })
        assertNull(round.backlogDueAt)
        assertEquals(emptyList<Instant>(), schedule.comeBacks)
    }

    /**
     * Строки, стоящие **за** нечитаемой, — её зависимые и следующие команды той же коробки, — тоже
     * ждут человека: пока он её не разберёт, их не отправит ни один заход. Приходить за ними
     * планировщику незачем так же, как за ней самой.
     *
     * Красная проверка: из остатка вычиталась только сама пропущенная строка, а следующая по
     * коробке оставалась «к сроку» — и заход за остатком ставился каждым заходом.
     */
    @Test
    fun rowsWaitingBehindAnUnreadableOneAskTheSchedulerForNothing() = runBlocking {
        unreadable()
        val next = Uuid.random()
        val dependent = Uuid.random()
        database.syncOperations().enqueue(next, PackageSyncCommand.ReleaseClaim(PACK), at)
        database.syncOperations().enqueue(dependent, PackageSyncCommand.ReleaseClaim(Uuid.random()), at, dependsOn = setOf(row))
        val schedule = FakeSyncSchedule()

        val round = FakeServer().synchronization(database, clock, schedule).synchronize()

        assertEquals(listOf(row), round.queue.skipped.map { it.id })
        assertNull(round.backlogDueAt)
        assertEquals(emptyList<Instant>(), schedule.comeBacks)
    }

    /**
     * Строке не хватило словаря, а дочитать его заход не смог — сервер не ответил. Это ожидание, а
     * не решение человека: следующий заход дочитает словарь, и строка уйдёт. Приходить за ней
     * планировщик должен.
     *
     * Красная проверка: такая строка попадала в пропущенные вместе с нечитаемыми, и заход за
     * остатком для неё не ставился — до следующего входа в приложение.
     */
    @Test
    fun aRowShortOfVocabularyWhenTheServerDidNotAnswerIsComeBackFor() = runBlocking {
        val foreign = QuantityUnit(Uuid.random(), "чужая единица")
        database.syncOperations().enqueue(row, PackageSyncCommand.Consume(PACK, dose(Quantity(BigDecimal.ONE, foreign)), INTAKE), at)
        val schedule = FakeSyncSchedule()

        val round = FakeServer().synchronization(database, clock, schedule).synchronize()

        assertEquals(emptyList<Uuid>(), round.queue.skipped.map { it.id })
        assertNotNull(round.backlogDueAt)
    }

    /** Строка чужой версии формата — такая, какой её оставляет обновление. */
    private suspend fun unreadable() {
        val stored = database.syncOperations().enqueue(row, PackageSyncCommand.Delete(PACK), at).toStorageEntity()
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId
            )
        )
    }
}
