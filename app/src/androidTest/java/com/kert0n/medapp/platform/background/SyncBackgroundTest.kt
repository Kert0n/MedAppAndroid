package com.kert0n.medapp.platform.background

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.fixture.FakeSettingsStore
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.settle
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.delivery.MedAppDoor
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SyncInterval
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.Synchronization
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.RoomTransactions
import com.kert0n.medapp.storage.operation.QueueBacklogRoomStorage
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Заходы без человека: регулярный и за остатком очереди. Планировщик системы ставит их по имени и
 * при связи, остаток очереди спрашивается у базы, а воркер делает то же, что вход в приложение
 * (PLAN E4).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SyncBackgroundTest {

    // Планировщик берётся из графа: настройка `WorkManager` — на весь процесс, и заводить её
    // вторым местом значит снова отдать её порядку классов (`fixture/TestWorkModule`).
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var work: WorkManager

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: MedAppDatabase
    private val snapshotReads = AtomicInteger()

    @Before
    fun setUp() {
        hilt.inject()
        database = inMemoryDatabase()
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
            QueueWorker(database.queueStorage(), MedAppCourier(MedAppDoor(api), vocabulary, resolver, clock), RoomTransactions(database), clock),
            SnapshotApplier(api, database.snapshotStorage(), vocabulary, resolver, clock),
            QueueBacklogRoomStorage(database.syncOperations()),
            schedule,
            clock,
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    private val settings = FakeSettingsStore()

    private fun worker(synchronization: Synchronization, comeBack: Boolean, at: Instant = now): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.COME_BACK to comeBack))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, synchronization, Scenarios(database, now).courseUpkeep, settings, Clock.fixed(at, ZoneOffset.UTC))
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
    fun theRegularRoundIsOneAndNeedsConnection() = runBlocking {
        val schedule = WorkManagerSyncSchedule({ work }, clock)

        schedule.keepRegular(SyncInterval.DEFAULT)
        schedule.keepRegular(SyncInterval.DEFAULT)

        val infos = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertEquals(Duration.ofHours(1).toMillis(), infos.single().periodicityInfo?.repeatIntervalMillis)
    }

    /**
     * Смена интервала обновляет ту же задачу, а не ставит вторую (PLAN E4). Красная проверка:
     * `KEEP` вместо сравнения — интервал остался бы час.
     */
    @Test
    fun aNewIntervalUpdatesTheSameWork() = runBlocking {
        val schedule = WorkManagerSyncSchedule({ work }, clock)
        schedule.keepRegular(SyncInterval.DEFAULT)
        val before = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get().single()

        schedule.keepRegular(SyncInterval(240))

        val infos = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get().filter { !it.state.isFinished }
        assertEquals(1, infos.size)
        assertEquals(before.id, infos.single().id)
        assertEquals(Duration.ofHours(4).toMillis(), infos.single().periodicityInfo?.repeatIntervalMillis)
    }

    /** Тот же интервал повторно — задача та же самая, в том же поколении: пересоздавать нечего. */
    @Test
    fun theSameIntervalRecreatesNothing() = runBlocking {
        val schedule = WorkManagerSyncSchedule({ work }, clock)
        schedule.keepRegular(SyncInterval(240))
        val before = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get().single()

        schedule.keepRegular(SyncInterval(240))

        val after = work.getWorkInfosForUniqueWork(WorkManagerSyncSchedule.REGULAR).get().single()
        assertEquals(before.id, after.id)
        assertEquals(before.generation, after.generation)
    }

    /** «Недавно» — половина выбранного интервала: при четырёх часах заход час спустя лишний, три спустя — нет. */
    @Test
    fun recentlyIsHalfOfTheChosenInterval() = runBlocking {
        val synchronization = synchronization(WorkManagerSyncSchedule({ work }, clock))
        synchronization.synchronize()
        val readsAfterEntry = snapshotReads.get()
        settings.saved = AppSettings(syncInterval = SyncInterval(240))

        worker(synchronization, comeBack = false, at = now.plus(Duration.ofHours(1))).doWork()
        assertEquals("час спустя при интервале в четыре — человек только что заходил", readsAfterEntry, snapshotReads.get())

        worker(synchronization, comeBack = false, at = now.plus(Duration.ofHours(3))).doWork()
        assertEquals("три часа спустя — больше полуинтервала, заход нужен", readsAfterEntry + 1, snapshotReads.get())
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
