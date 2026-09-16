package com.kert0n.medapp.di

import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.platform.notifications.NotificationChannels
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.platform.notifications.AlarmManagerReminders
import com.kert0n.medapp.platform.notifications.WorkManagerDailySchedule
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.platform.notifications.SystemNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Уведомления: домен называет действия портами, платформа их исполняет (PLAN D8, H1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    @Binds
    @Singleton
    abstract fun notifier(implementation: SystemNotifier): Notifier

    @Binds
    @Singleton
    abstract fun readiness(implementation: NotificationChannels): NotificationReadiness

    @Binds
    @Singleton
    abstract fun reminderAlarms(implementation: AlarmManagerReminders): ReminderAlarms

    @Binds
    @Singleton
    abstract fun freshness(implementation: com.kert0n.medapp.queue.Synchronization): Freshness

    @Binds
    @Singleton
    abstract fun dailySchedule(implementation: WorkManagerDailySchedule): DailySchedule

    /**
     * Весть о переводе часов — тот же системный сигнал, что будит проход дня: ждущие границы
     * суток видят его портом, а приносит его платформа (`BootAndTimeReceiver`).
     */
    @Binds
    @Singleton
    abstract fun clockShifts(implementation: TimeShifts): ClockShifts
}
