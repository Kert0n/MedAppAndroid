package com.kert0n.medapp.fixture

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
 * Падало это не всегда: один класс (`SyncBackgroundTest`) настраивает испытательный `WorkManager`
 * сам, и настройка эта на **весь процесс** — прогнанный раньше, он чинил соседей, а прогнанный
 * позже или отдельно оставлял их без планировщика. Проверка, зелёная от порядка классов, ничего
 * не утверждает, поэтому настройка переезжает в граф: испытательный `WorkManager` заводится один
 * раз и достаётся всем.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkModule::class])
object TestWorkModule {

    @Provides
    @Singleton
    fun workManager(): WorkManager {
        // Настройка процессу одна: заводит её тот, кто пришёл первым, остальные берут готовую.
        // Контекст тот же, каким настраивает `SyncBackgroundTest`, — иначе настроек стало бы две.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        if (runCatching { WorkManager.getInstance(target) }.isFailure) {
            WorkManagerTestInitHelper.initializeTestWorkManager(target, Configuration.Builder().build())
        }
        return WorkManager.getInstance(target)
    }

    @Provides
    @Singleton
    fun syncSchedule(implementation: WorkManagerSyncSchedule): SyncSchedule = implementation
}
