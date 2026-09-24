package com.kert0n.medapp.di

import android.content.Context
import androidx.work.WorkManager
import com.kert0n.medapp.feature.delivery.SyncSchedule
import com.kert0n.medapp.platform.background.WorkManagerSyncSchedule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Фоновые заходы выполняет планировщик системы; очередь видит порт (PLAN E4, H1). */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun syncSchedule(implementation: WorkManagerSyncSchedule): SyncSchedule = implementation
}
