package com.kert0n.medapp.di

import com.kert0n.medapp.app.background.SyncWorker
import com.kert0n.medapp.app.notifications.DailyWorker
import com.kert0n.medapp.app.notifications.NotificationActionReceiver
import com.kert0n.medapp.app.notifications.ReminderWakeReceiver
import com.kert0n.medapp.platform.AppEntries
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Входы Android, которые будит платформа, называет сборка: платформа их не видит. */
@Module
@InstallIn(SingletonComponent::class)
object EntriesModule {

    @Provides
    fun entries(): AppEntries = AppEntries(
        sync = SyncWorker::class.java,
        daily = DailyWorker::class.java,
        reminderWake = ReminderWakeReceiver::class.java,
        notificationAction = NotificationActionReceiver::class.java
    )
}
