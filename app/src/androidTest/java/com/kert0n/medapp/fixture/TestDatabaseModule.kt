package com.kert0n.medapp.fixture

import android.content.Context
import androidx.room.Room
import com.kert0n.medapp.di.ArrivedMedKitName
import com.kert0n.medapp.di.DatabaseModule
import com.kert0n.medapp.storage.database.MedAppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

/**
 * База сквозных проверок живёт в памяти: настоящая осталась бы от прогона к прогону, и проверка,
 * прошедшая на чужих данных, ничего не доказывает.
 *
 * Подменяется весь [DatabaseModule]: у него база и все её DAO, и подменить одну дверь значило бы
 * оставить остальные смотреть в файл на устройстве.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MedAppDatabase =
        Room.inMemoryDatabaseBuilder(context, MedAppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** Имя, которым называется прилетевшая общая аптечка: его тоже давал подменённый модуль. */
    @Provides
    @ArrivedMedKitName
    fun arrivedMedKitName(@ApplicationContext context: Context): String =
        context.getString(com.kert0n.medapp.R.string.med_kit_arrived_name)

    @Provides
    fun vocabularyDao(database: MedAppDatabase) = database.vocabulary()

    @Provides
    fun templateDao(database: MedAppDatabase) = database.templates()

    @Provides
    fun medKitDao(database: MedAppDatabase) = database.medKits()

    @Provides
    fun packageDao(database: MedAppDatabase) = database.packages()

    @Provides
    fun courseDao(database: MedAppDatabase) = database.courses()

    @Provides
    fun intakeDao(database: MedAppDatabase) = database.intakes()

    @Provides
    fun syncOperationDao(database: MedAppDatabase) = database.syncOperations()

    @Provides
    fun reminderDao(database: MedAppDatabase) = database.reminders()
}
