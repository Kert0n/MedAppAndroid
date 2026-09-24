package com.kert0n.medapp.di

import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.value.VocabularyStore
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.feature.course.CourseReadings
import com.kert0n.medapp.feature.course.CourseRecords
import com.kert0n.medapp.feature.intake.IntakeAccounts
import com.kert0n.medapp.feature.intake.IntakeReadings
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.medkits.MedKitReadings
import com.kert0n.medapp.feature.medkits.MedKitRecords
import com.kert0n.medapp.feature.notification.ReminderReadings
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.feature.operation.OperationReadings
import com.kert0n.medapp.feature.operation.OperationRecords
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.report.ReportReadings
import com.kert0n.medapp.feature.template.TemplateRecords
import com.kert0n.medapp.feature.value.VocabularyReadings
import com.kert0n.medapp.queue.QueueBacklog
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseRoomRepository
import com.kert0n.medapp.storage.database.RoomTransactions
import com.kert0n.medapp.storage.intake.IntakeRoomRepository
import com.kert0n.medapp.storage.medkit.MedKitRoomRepository
import com.kert0n.medapp.storage.notification.ReminderRoomRepository
import com.kert0n.medapp.storage.operation.IntakeAccountsRoomRepository
import com.kert0n.medapp.storage.operation.QueueBacklogRoomStorage
import com.kert0n.medapp.storage.operation.QueueRoomStorage
import com.kert0n.medapp.storage.operation.SyncOperationRoomRepository
import com.kert0n.medapp.storage.pack.PackageRoomRepository
import com.kert0n.medapp.storage.report.ReportRoomRepository
import com.kert0n.medapp.storage.snapshot.SnapshotRoomStorage
import com.kert0n.medapp.storage.template.PackageTemplateRoomRepository
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Хранение выдаётся графом интерфейсом, а не реализацией: экрану нужен ответ на вопрос, а не
 * Room. Тому же служит подмена в тестах представления, где базы нет вовсе (PLAN H1).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun medKits(implementation: MedKitRoomRepository): MedKitRecords

    @Binds
    abstract fun medKitsReadings(implementation: MedKitRoomRepository): MedKitReadings

    @Binds
    @Singleton
    abstract fun packages(implementation: PackageRoomRepository): PackageRecords

    @Binds
    abstract fun packageReadings(implementation: PackageRoomRepository): PackageReadings

    @Binds
    @Singleton
    abstract fun courses(implementation: CourseRoomRepository): CourseRecords

    @Binds
    abstract fun coursesReadings(implementation: CourseRoomRepository): CourseReadings

    @Binds
    @Singleton
    abstract fun intakes(implementation: IntakeRoomRepository): IntakeRecords

    @Binds
    abstract fun intakesReadings(implementation: IntakeRoomRepository): IntakeReadings

    @Binds
    abstract fun intakeAccounts(implementation: IntakeAccountsRoomRepository): IntakeAccounts

    @Binds
    @Singleton
    abstract fun syncOperations(implementation: SyncOperationRoomRepository): OperationRecords

    @Binds
    abstract fun syncOperationsReadings(implementation: SyncOperationRoomRepository): OperationReadings

    @Binds
    @Singleton
    abstract fun vocabulary(implementation: VocabularyRoomRepository): VocabularyReadings

    @Binds
    @Singleton
    abstract fun reports(implementation: ReportRoomRepository): ReportReadings

    @Binds
    @Singleton
    abstract fun templates(implementation: PackageTemplateRoomRepository): TemplateRecords

    /** «Одна транзакция» — узкий порт: сценарию незачем видеть порт работника очереди (PLAN F5). */
    @Binds
    @Singleton
    abstract fun transactions(implementation: RoomTransactions): Transactions

    /** Работник очереди видит хранилище через свой порт; транзакции очереди остаются в хранении. */
    @Binds
    @Singleton
    abstract fun queueStorage(implementation: QueueRoomStorage): QueueStorage

    /** Остаток очереди — планировщику заходов без живого процесса (PLAN E4). */
    @Binds
    @Singleton
    abstract fun queueBacklog(implementation: QueueBacklogRoomStorage): QueueBacklog

    /** Чтение полного снимка кладёт его через свой порт — одной транзакцией (PLAN E4). */
    @Binds
    @Singleton
    abstract fun snapshotStorage(implementation: SnapshotRoomStorage): SnapshotStorage

    /** Курс следует за коробкой: действие называет домен, исполняет прикладной владелец (PLAN D5). */
    @Binds
    abstract fun packageFollowing(implementation: CourseFollowing): PackageFollowing

    /** Резолвер словаря живёт в сети и получает снимок через свой интерфейс. */
    @Binds
    @Singleton
    abstract fun vocabularyStore(implementation: VocabularyRoomRepository): VocabularyStore

    @Binds
    @Singleton
    abstract fun reminders(implementation: ReminderRoomRepository): ReminderRecords

    @Binds
    abstract fun remindersReadings(implementation: ReminderRoomRepository): ReminderReadings
}
