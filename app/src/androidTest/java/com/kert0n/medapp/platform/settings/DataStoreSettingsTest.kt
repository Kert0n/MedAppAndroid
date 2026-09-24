package com.kert0n.medapp.platform.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SettingsSaved
import com.kert0n.medapp.feature.settings.SyncInterval
import java.io.File
import java.time.LocalTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Настройки лежат в файле, переживают пересоздание и читаются умолчаниями, когда файл не
 * читается, — а сам файл чтение не трогает (PLAN D8, C1 «Повреждённый файл настроек»).
 */
class DataStoreSettingsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "settings.test.${Uuid.random()}"
    private val chosen = AppSettings(
        notifications = NotificationSettings(
            intakeRemindersEnabled = false,
            snoozeMinutes = 20,
            expirySourceRemindersEnabled = false,
            coverageThresholdDays = 5,
            digestEnabled = true,
            digestAt = LocalTime.of(18, 30),
            remoteChangeEnabled = false
        ),
        syncInterval = SyncInterval(270) // не круглое в часах: хранилище не должно округлять
    )

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var settings: DataStoreSettings

    @Before
    fun openStore() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(context.cacheDir, "$name.preferences_pb")
        settings = DataStoreSettings(store(), file, Dispatchers.IO)
    }

    @After
    fun closeStore() {
        scope.cancel()
        file.delete()
    }

    private fun store(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    @Test
    fun emptyStoreAnswersWithDefaults() = runTest {
        assertEquals(AppSettings.DEFAULT, settings.current())
        assertEquals(NotificationSettings.DEFAULT, settings.current().notifications)
        assertEquals(SyncInterval.DEFAULT, settings.current().syncInterval)
    }

    /** Второе хранилище над тем же файлом — это и есть новый процесс. */
    @Test
    fun savedSettingsSurviveRecreation() = runTest {
        assertEquals(SettingsSaved.SAVED, settings.save(chosen))
        // Прежнее хранилище отпускает файл, когда его область **кончилась**, а не когда её отменили:
        // открыть новое раньше — «несколько DataStore на один файл».
        scope.coroutineContext.job.cancelAndJoin()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val reopened = DataStoreSettings(store(), file, Dispatchers.IO)

        assertEquals(chosen, reopened.current())
    }

    /**
     * Повреждённый файл: читается умолчаниями, и байты его после чтения те же. Красная проверка:
     * `ReplaceFileCorruptionHandler` у хранилища — файл стал бы пустым, а ниже `save` не понадобилось
     * бы удаление.
     */
    @Test
    fun damagedFileIsReadAsDefaultsAndLeftAlone() = runTest {
        settings.save(chosen)
        val garbage = byteArrayOf(0x4D, 0x65, 0x64, 0x41, 0x70, 0x70, 0x21, 0x00, 0x7F)
        file.writeBytes(garbage)
        // Прежнее хранилище отпускает файл, когда его область **кончилась**, а не когда её отменили:
        // открыть новое раньше — «несколько DataStore на один файл».
        scope.coroutineContext.job.cancelAndJoin()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val damaged = DataStoreSettings(store(), file, Dispatchers.IO)

        assertEquals(AppSettings.DEFAULT, damaged.current())
        assertArrayEquals("чтение переписало повреждённый файл", garbage, file.readBytes())
    }

    /** Заново файл кладёт только запись человека — и после неё настройки читаются. */
    @Test
    fun savingOverADamagedFileLaysItAgain() = runTest {
        file.writeBytes(byteArrayOf(0x4D, 0x65, 0x64, 0x41, 0x70, 0x70, 0x21, 0x00, 0x7F))
        val damaged = DataStoreSettings(store(), file, Dispatchers.IO)
        assertEquals(AppSettings.DEFAULT, damaged.current())

        assertEquals(SettingsSaved.SAVED, damaged.save(chosen))

        assertEquals(chosen, damaged.current())
    }
}
