package com.kert0n.medapp.platform.background

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueHttpTransport
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.Synchronization
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.server.QueueBacklogRoomStorage
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Заходы без человека: регулярный и за остатком очереди. Планировщик системы ставит их по имени и
 * при связи, остаток очереди спрашивается у базы, а воркер делает то же, что вход в приложение
 * (PLAN E4).
 */
@RunWith(AndroidJUnit4::class)
class SyncBackgroundTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: MedAppDatabase
    private lateinit var work: WorkManager
    private val snapshotReads = AtomicInteger()

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        work = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = database.close()

    private fun synchronization(schedule: WorkManagerSyncSchedule): Synchronization {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine {
                    snapshotReads.incrementAndGet()
                    respond("""{"id":"${Uuid.random()}","medKits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        val resolver = PackageSnapshotResolver(vocabulary, database.queueStorage())
        return Synchronization(
            QueueWorker(database.queueStorage(), QueueHttpTransport(api), vocabulary, resolver, clock),
            SnapshotApplier(api, database.snapshotStorage(), vocabulary, resolver, clock),
            QueueBacklogRoomStorage(database.syncOperations()),
            schedule,
            clock,
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    private fun worker(synchronization: Synchronization, comeBack: Boolean): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.COME_BACK to comeBack))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, synchronization, clock)
            })
            .build()

    /** Остаток очереди — вопрос к базе: пусто — `null`, ждущая связи — «уже пора», отложенная — её срок. */
    @Test
    fun theBacklogIsAskedOfTheDatabase() = runBlocking {
        val backlog = QueueBacklogRoomStorage(database.syncOperations())
        assertNull(backlog.dueAt(now))

        val waiting = Uuid.random()
        database.syncOperations().enqueue(waiting, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)
        assertEquals(Instant.EPOCH, backlog.dueAt(now))

        database.syncOperations().settle(waiting, SyncOperationStatus.PENDING, "повтор позже", now, notBefore = now.plusSeconds(300))
        assertEquals(now.plusSeconds(300), backlog.dueAt(now))

        database.syncOperations().settle(waiting, SyncOperationStatus.APPLIED)
        assertNull(backlog.dueAt(now))
    }

    /** Регулярный заход один на процесс и при связи: повторный вызов его не сдвигает и не множит. */
    @Test
    fun theRegularRoundIsOneAndNeedsConnection() {
        val schedule = WorkManagerSyncSchedule({ work }, clock)

        schedule.keepRegular()
        schedule.keepRegular()

        val infos = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    /** За остатком приходят один раз, при связи, с пометкой «за остатком». */
    @Test
    fun comingBackForTheBacklogIsOneRoundWithConnection() {
        val schedule = WorkManagerSyncSchedule({ work }, clock)

        schedule.comeBackFor(now.plusSeconds(60))
        schedule.comeBackFor(now)

        val infos = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.COME_BACK).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    /** Человек только что заходил, и снимок лёг: регулярный заход сервер не спрашивает. */
    @Test
    fun aRegularRoundRightAfterEntryIsSkipped() = runBlocking {
        val synchronization = synchronization(WorkManagerSyncSchedule({ work }, clock))
        synchronization.synchronize()
        val readsAfterEntry = snapshotReads.get()

        val result = worker(synchronization, comeBack = false).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(readsAfterEntry, snapshotReads.get())
    }

    /**
     * Заход за остатком: очередь не опустела — систему просят повторить при связи, а пустая очередь
     * заход закрывает.
     */
    @Test
    fun aRoundForTheBacklogRetriesUntilTheQueueIsEmpty() = runBlocking {
        val synchronization = synchronization(WorkManagerSyncSchedule({ work }, clock))
        val stuck = Uuid.random()
        database.syncOperations().enqueue(stuck, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)
        database.syncOperations().settle(stuck, SyncOperationStatus.PENDING, "ждёт", now, notBefore = now.plusSeconds(600))

        assertEquals(ListenableWorker.Result.retry(), worker(synchronization, comeBack = true).doWork())
        assertTrue(work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.COME_BACK).get().isNotEmpty())

        database.syncOperations().settle(stuck, SyncOperationStatus.APPLIED)
        assertEquals(ListenableWorker.Result.success(), worker(synchronization, comeBack = true).doWork())
    }
}
