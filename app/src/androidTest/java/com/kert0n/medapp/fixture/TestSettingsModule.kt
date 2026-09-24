package com.kert0n.medapp.fixture

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kert0n.medapp.di.IoDispatcher
import com.kert0n.medapp.di.SettingsFile
import com.kert0n.medapp.di.SettingsModule
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Readiness
import com.kert0n.medapp.feature.settings.AppLanguages
import com.kert0n.medapp.feature.settings.CameraAccess
import com.kert0n.medapp.feature.settings.DevicePermissions
import com.kert0n.medapp.feature.settings.PermissionStates
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.platform.settings.DataStoreSettings
import com.kert0n.medapp.platform.settings.StoredNotificationSettings
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

    /**
     * Разрешения проверкам называет [TestPermissions], а не система: отзывать их у приложения
     * нельзя — Android убивает процесс, и проверка падает не о том, о чём написана. А история
     * человека, забывшего включить уведомления, — ровно о том, что видно при отказе.
     */
    @Provides
    @Singleton
    fun permissions(): DevicePermissions = TestPermissions

    /** Язык проверкам называет подделка: настоящий выбор на 13+ перевёл бы весь прогон. */
    @Provides
    @Singleton
    fun languages(): AppLanguages = TestLanguages
}

/** Язык, «выбранный» в проверке; после каждой истории возвращается к «как в системе». */
val TestLanguages = FakeAppLanguages()

/**
 * Что система «разрешила» в проверке. По умолчанию — всё: истории, которым разрешения безразличны,
 * о них и не говорят. Отказ называется явно, и после каждой истории состояние возвращается
 * ([reset]): граф заводится заново, а объект живёт весь прогон.
 */
object TestPermissions : DevicePermissions, NotificationReadiness {

    var notifications: Boolean = true
    var exactAlarms: Boolean = true

    /** Каналы, заглушённые человеком долгим нажатием на карточку. */
    var muted: Set<NotificationChannel> = emptySet()

    /** Тот же ответ, что читают показ и «День»: разрешение приложению и заглушённые каналы. */
    override fun now(): Readiness = Readiness(notifications, muted)

    override fun current(): PermissionStates = states()

    private fun states(): PermissionStates =
        // Точность будильников у истории одна — у её постановок: спросить второй ответ значило бы
        // завести в проверке то расхождение, которое приложение как раз не допускает.
        PermissionStates(
            notifications = notifications,
            exactAlarms = StoryWorld.current?.alarms?.canBeExact ?: exactAlarms,
            camera = CameraAccess.GRANTED
        )

    fun reset() {
        notifications = true
        exactAlarms = true
        muted = emptySet()
    }
}
