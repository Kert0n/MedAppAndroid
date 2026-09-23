package com.kert0n.medapp.fixture

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.di.WorkModule
import com.kert0n.medapp.queue.SyncSchedule
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Планировщик системы для проверок. Настройка `WorkManager` — на **весь процесс**, и заводит её
 * раннер ([freshTestWorkManager] в `HiltTestRunner`) до любого компонента: задание настоящей
 * установки система отдаёт процессу пакета по сроку, и `SystemJobService` должен найти, кому его
 * отдать, ещё до первой проверки. Граф отдаёт уже заведённый.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkModule::class])
object TestWorkModule {

    @Provides
    @Singleton
    fun workManager(): WorkManager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * Заход без человека в проверках **не ставится**. Испытательный `WorkManager` выполняет
     * поставленное сразу и на том же потоке, и `Synchronization`, оставив что-то в очереди,
     * запустила бы настоящий заход — под `-Pprobe` приложение живёт пробной учёткой, и заход
     * ушёл бы в боевой сервер. Кому нужен настоящий планировщик, собирает
     * `WorkManagerSyncSchedule` сам (`SyncBackgroundTest`) — там он и проверяется.
     */
    @Provides
    @Singleton
    fun syncSchedule(): SyncSchedule = FakeSyncSchedule()
}

/**
 * Испытательный `WorkManager` процессу — его заводит раннер до любого компонента. Фабрика у него
 * одна на все проверки, а графы у проверок свои, поэтому работника она берёт у
 * графа той проверки, что идёт **сейчас** (`SyncWorker` и `DailyWorker` собираются графом,
 * `@HiltWorker`). Графа нет — работника тоже: WorkManager отметит работу неудавшейся, а процесс
 * останется жив.
 */
fun freshTestWorkManager(context: Context): WorkManager {
    val app = context.applicationContext
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setWorkerFactory(CurrentGraphWorkers(app)).build())
    return WorkManager.getInstance(app)
}

private class CurrentGraphWorkers(private val app: Context) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        runCatching { EntryPoints.get(app, Workers::class.java).factory() }.getOrNull()
            ?.createWorker(appContext, workerClassName, workerParameters)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Workers {
        fun factory(): HiltWorkerFactory
    }
}
