package com.kert0n.medapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.feature.settings.AppLanguages
import com.kert0n.medapp.feature.settings.DevicePermissions
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.platform.settings.AndroidDevicePermissions
import com.kert0n.medapp.platform.settings.AppCompatAppLanguages
import com.kert0n.medapp.platform.settings.DataStoreSettings
import com.kert0n.medapp.platform.settings.StoredNotificationSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsFile

/**
 * Настройки хранит платформа в своём файле DataStore; и домену (`NotificationSettingsSource`), и
 * сценариям с экраном (`SettingsStore`) отвечает одно хранилище — иначе два разошлись бы в ответе
 * на один вопрос (PLAN D8).
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    @SettingsFile
    fun settingsFile(@ApplicationContext context: Context): File = context.preferencesDataStoreFile("settings")

    @Provides
    @Singleton
    fun settings(@SettingsFile file: File, @IoDispatcher io: CoroutineDispatcher): DataStoreSettings =
        DataStoreSettings(PreferenceDataStoreFactory.create { file }, file, io)

    @Provides
    @Singleton
    fun settingsStore(implementation: DataStoreSettings): SettingsStore = implementation

    @Provides
    @Singleton
    fun notificationSettings(implementation: StoredNotificationSettings): NotificationSettingsSource = implementation

    @Provides
    @Singleton
    fun permissions(implementation: AndroidDevicePermissions): DevicePermissions = implementation

    @Provides
    @Singleton
    fun languages(implementation: AppCompatAppLanguages): AppLanguages = implementation
}
