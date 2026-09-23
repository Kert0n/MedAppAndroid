package com.kert0n.medapp.di

import android.content.Context
import androidx.room.Room
import com.kert0n.medapp.R
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.BundledVocabulary
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.notification.ReminderDao
import com.kert0n.medapp.storage.operation.SyncOperationDao
import com.kert0n.medapp.storage.template.PackageTemplateDao
import com.kert0n.medapp.storage.value.VocabularyDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Имя, под которым у нас появляется полка, пришедшая с сервера: названия полок сервер не хранит,
 * и человек переименует её сам (PLAN C0). Это текст для человека — он живёт в ресурсах.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArrivedMedKitName

/**
 * База поднимается графом в одном экземпляре: два экземпляра над одним файлом не видели бы
 * транзакций друг друга.
 *
 * Разрушающий откат не включается ни в каком виде: он молча стирает очередь и историю при
 * обновлении приложения, и вместо потери данных нужен упавший переход (PLAN F4). При создании
 * база сразу получает встроенный снимок словарей.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun medAppDatabase(@ApplicationContext context: Context): MedAppDatabase =
        Room.databaseBuilder(context, MedAppDatabase::class.java, MedAppDatabase.NAME)
            .addCallback(BundledVocabulary.fromAssets(context.assets))
            .addMigrations(*MedAppDatabase.MIGRATIONS)
            .build()

    @Provides
    @ArrivedMedKitName
    fun arrivedMedKitName(@ApplicationContext context: Context): String =
        context.getString(R.string.med_kit_arrived_name)

    @Provides
    fun vocabularyDao(database: MedAppDatabase): VocabularyDao = database.vocabulary()

    @Provides
    fun templateDao(database: MedAppDatabase): PackageTemplateDao = database.templates()

    @Provides
    fun medKitDao(database: MedAppDatabase): MedKitDao = database.medKits()

    @Provides
    fun packageDao(database: MedAppDatabase): PackageDao = database.packages()

    @Provides
    fun courseDao(database: MedAppDatabase): CourseDao = database.courses()

    @Provides
    fun intakeDao(database: MedAppDatabase): IntakeDao = database.intakes()

    @Provides
    fun syncOperationDao(database: MedAppDatabase): SyncOperationDao = database.syncOperations()

    @Provides
    fun reminderDao(database: MedAppDatabase): ReminderDao = database.reminders()
}
