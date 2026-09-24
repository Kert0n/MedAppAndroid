package com.kert0n.medapp.fixture

import com.kert0n.medapp.di.ClockModule
import com.kert0n.medapp.di.NotificationModule
import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.feature.delivery.Synchronization
import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.platform.notifications.AlarmManagerReminders
import com.kert0n.medapp.platform.notifications.SystemNotifier
import com.kert0n.medapp.platform.notifications.WorkManagerDailySchedule
import com.kert0n.medapp.platform.time.DeviceClock
import com.kert0n.medapp.platform.time.TimeShifts
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Clock
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Часы и уведомления сквозных проверок. Вне истории граф получает **настоящие** реализации —
 * приёмникам и показу проверять больше нечего. Идёт история ([StoryWorld.current]) — граф получает её
 * часы, шторку и постановки: время приложения двигает рассказ, а что сказано, видно без системы.
 *
 * Выбор делается при сборке графа, а граф заводится заново на каждый тест: история начинается
 * раньше первого `inject` и кончается после теста, как [TestPermissions].
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NotificationModule::class, ClockModule::class])
object TestNotificationModule {

    @Provides
    @Singleton
    fun clock(): Clock = StoryWorld.current?.clock ?: DeviceClock

    @Provides
    @Singleton
    fun notifier(real: Provider<SystemNotifier>): Notifier = StoryWorld.current?.shade ?: real.get()

    @Provides
    @Singleton
    fun reminderAlarms(real: Provider<AlarmManagerReminders>): ReminderAlarms = StoryWorld.current?.alarms ?: real.get()

    @Provides
    @Singleton
    fun freshness(real: Provider<Synchronization>): Freshness = StoryWorld.current?.freshness ?: real.get()

    @Provides
    @Singleton
    fun dailySchedule(real: Provider<WorkManagerDailySchedule>): DailySchedule = StoryWorld.current?.daily ?: real.get()

    /**
     * Можно ли сказать — у проверок отвечает [TestPermissions]: настоящее разрешение отозвать нельзя,
     * Android убивает процесс, а истории об отказе о нём и написаны.
     */
    @Provides
    @Singleton
    fun readiness(): NotificationReadiness = TestPermissions

    /** Весть о переводе часов — всегда настоящая: история шлёт её сама, когда двигает часы. */
    @Provides
    @Singleton
    fun clockShifts(real: TimeShifts): ClockShifts = real
}
