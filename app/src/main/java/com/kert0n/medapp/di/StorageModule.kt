package com.kert0n.medapp.di

import com.kert0n.medapp.storage.course.CourseRoomRepository
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeRoomRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitRoomRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageRoomRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.server.QueueBacklogRoomStorage
import com.kert0n.medapp.storage.server.QueueRoomStorage
import com.kert0n.medapp.storage.server.SnapshotRoomStorage
import com.kert0n.medapp.storage.notification.ReminderRoomRepository
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.storage.server.SyncOperationRoomRepository
import com.kert0n.medapp.storage.server.SyncOperationStorageRepository
import com.kert0n.medapp.network.value.VocabularyStore
import com.kert0n.medapp.queue.QueueBacklog
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.database.RoomTransactions
import com.kert0n.medapp.storage.report.ReportRoomRepository
import com.kert0n.medapp.storage.report.ReportStorageRepository
import com.kert0n.medapp.storage.template.PackageTemplateRoomRepository
import com.kert0n.medapp.storage.template.PackageTemplateStorageRepository
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
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
    abstract fun medKits(implementation: MedKitRoomRepository): MedKitStorageRepository

    @Binds
    @Singleton
    abstract fun packages(implementation: PackageRoomRepository): PackageStorageRepository

    @Binds
    @Singleton
    abstract fun courses(implementation: CourseRoomRepository): CourseStorageRepository

    @Binds
    @Singleton
    abstract fun intakes(implementation: IntakeRoomRepository): IntakeStorageRepository

    @Binds
    @Singleton
    abstract fun syncOperations(
        implementation: SyncOperationRoomRepository
    ): SyncOperationStorageRepository

    @Binds
    @Singleton
    abstract fun vocabulary(implementation: VocabularyRoomRepository): VocabularyStorageRepository

    @Binds
    @Singleton
    abstract fun reports(implementation: ReportRoomRepository): ReportStorageRepository

    @Binds
    @Singleton
    abstract fun templates(implementation: PackageTemplateRoomRepository): PackageTemplateStorageRepository

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

    /** Резолвер словаря живёт в сети и получает снимок через свой интерфейс. */
    @Binds
    @Singleton
    abstract fun vocabularyStore(implementation: VocabularyRoomRepository): VocabularyStore

    @Binds
    @Singleton
    abstract fun reminders(implementation: ReminderRoomRepository): ReminderStorageRepository
}
