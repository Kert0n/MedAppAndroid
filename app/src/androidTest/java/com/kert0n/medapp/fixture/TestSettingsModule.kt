package com.kert0n.medapp.fixture

import android.content.Context
import com.kert0n.medapp.di.IoDispatcher
import com.kert0n.medapp.di.SettingsFile
import com.kert0n.medapp.di.SettingsModule
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.platform.settings.AndroidDevicePermissions
import com.kert0n.medapp.platform.settings.DataStoreSettings
import com.kert0n.medapp.platform.settings.DevicePermissions
import com.kert0n.medapp.platform.settings.StoredNotificationSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Настройки сквозных проверок — в своём файле на каждый тест. У тестового приложения граф
 * заводится заново на каждый тест, а `DataStore` прежнего теста остаётся жив: два хранилища на
 * одном файле — исключение, и падает не то, что проверяют, а соседнее чтение настроек. Пока
 * настройки открывал один тест на прогон, это не было видно; с историями их стало два.
 *
 * Файл лежит в `cacheDir` и с боевым не пересекается: проверкам важно, что хранилище своё, а не
 * где оно.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsModule::class])
object TestSettingsModule {

    @Provides
    @Singleton
    @SettingsFile
    fun settingsFile(@ApplicationContext context: Context): File =
        File(context.cacheDir, "settings-${Uuid.random()}.preferences_pb")

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
}
