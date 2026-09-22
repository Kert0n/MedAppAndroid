package com.kert0n.medapp.fixture

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.di.WorkModule
import com.kert0n.medapp.platform.background.WorkManagerSyncSchedule
import com.kert0n.medapp.queue.SyncSchedule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Планировщик системы для проверок: настоящий `WorkManager` настраивает себе **приложение**
 * (`MedApp.onCreate`), а в проверках живёт `HiltTestApplication` — его `onCreate` не бежит, и
 * `WorkManager.getInstance` роняет проверку словами «WorkManager is not initialized properly».
 *
 * Падало это не всегда: настройка `WorkManager` — на **весь процесс**, и до сих пор её делал тот
 * класс, который прогнали первым. Проверка, зелёная от порядка классов, ничего не утверждает,
 * поэтому настройка переезжает в граф: испытательный `WorkManager` заводится один раз и
 * достаётся всем.
 *
 * Фабрика — **та же**, что у приложения: `SyncWorker` и `DailyWorker` собираются графом
 * (`@HiltWorker`), и пустая настройка создать их не смогла бы. Сейчас их никто в проверках не
 * запускает, но это случайность порядка, а не правило, — и именно такие случайности здесь уже
 * один раз покраснели.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkModule::class])
object TestWorkModule {

    @Provides
    @Singleton
    fun workManager(factory: HiltWorkerFactory): WorkManager {
        // Настройка процессу одна: заводит её тот, кто пришёл первым, остальные берут готовую.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        if (runCatching { WorkManager.getInstance(target) }.isFailure) {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                target,
                Configuration.Builder().setWorkerFactory(factory).build()
            )
        }
        return WorkManager.getInstance(target)
    }

    @Provides
    @Singleton
    fun syncSchedule(implementation: WorkManagerSyncSchedule): SyncSchedule = implementation
}
