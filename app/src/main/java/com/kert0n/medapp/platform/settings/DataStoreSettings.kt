package com.kert0n.medapp.platform.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.kert0n.medapp.di.IoDispatcher
import com.kert0n.medapp.di.SettingsFile
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SettingsSaved
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.feature.settings.SyncInterval
import java.io.File
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Настройки в DataStore (PLAN D8, C1). Собирается графом (`SettingsModule`), а не сам по себе:
 * файл нужен и хранилищу, и записи, которая его пересоздаёт. Один экземпляр исполняет оба порта: доменный — для сверки и
 * ответов из шторки, и [SettingsStore] — для экрана, фонового захода и постановки задач при старте.
 *
 * Повреждённый файл читается умолчаниями и **не переписывается чтением**: обработчика, молча
 * заменяющего файл пустым, здесь нет (довод G2). Заново его кладёт только запись — она и есть
 * решение человека о всём наборе, и делает это открыто: файл, который не читается, удаляется и
 * пишется снова. Значение вне пределов в файле — тот же случай, что и повреждение: правило живёт на
 * типе, и чтение отдаёт умолчание, а не бросает.
 */
class DataStoreSettings(
    private val store: DataStore<Preferences>,
    @SettingsFile private val file: File,
    @IoDispatcher private val io: CoroutineDispatcher
) : SettingsStore {

    override fun observe(): Flow<AppSettings> = store.data
        .map { it.toSettings() }
        .catch { failure -> if (failure is IOException) emit(AppSettings.DEFAULT) else throw failure }

    override suspend fun current(): AppSettings = withContext(io) { observe().first() }

    override suspend fun save(settings: AppSettings): SettingsSaved = withContext(io) {
        try {
            write(settings)
        } catch (_: CorruptionException) {
            // Файл не читается, и правка поверх него невозможна: запись кладёт его заново.
            if (!file.delete()) return@withContext SettingsSaved.LOST
            try {
                write(settings)
            } catch (_: IOException) {
                SettingsSaved.LOST
            }
        } catch (_: IOException) {
            SettingsSaved.LOST
        }
    }

    private suspend fun write(settings: AppSettings): SettingsSaved {
        store.edit { it.put(settings) }
        return SettingsSaved.SAVED
    }

    private fun MutablePreferences.put(settings: AppSettings) {
        val n = settings.notifications
        this[INTAKE_REMINDERS] = n.intakeRemindersEnabled
        this[SNOOZE_MINUTES] = n.snoozeMinutes
        this[EXPIRY_SOURCE_REMINDERS] = n.expirySourceRemindersEnabled
        this[COVERAGE_THRESHOLD_DAYS] = n.coverageThresholdDays
        this[DIGEST_ENABLED] = n.digestEnabled
        this[DIGEST_AT_SECOND] = n.digestAt.toSecondOfDay()
        this[REMOTE_CHANGE] = n.remoteChangeEnabled
        this[SYNC_INTERVAL_MINUTES] = settings.syncInterval.minutes
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = NotificationSettings.DEFAULT
        val notifications = attempt {
            NotificationSettings(
                intakeRemindersEnabled = this[INTAKE_REMINDERS] ?: defaults.intakeRemindersEnabled,
                snoozeMinutes = this[SNOOZE_MINUTES] ?: defaults.snoozeMinutes,
                expirySourceRemindersEnabled = this[EXPIRY_SOURCE_REMINDERS] ?: defaults.expirySourceRemindersEnabled,
                coverageThresholdDays = this[COVERAGE_THRESHOLD_DAYS] ?: defaults.coverageThresholdDays,
                digestEnabled = this[DIGEST_ENABLED] ?: defaults.digestEnabled,
                digestAt = this[DIGEST_AT_SECOND]?.let { LocalTime.ofSecondOfDay(it.toLong()) } ?: defaults.digestAt,
                remoteChangeEnabled = this[REMOTE_CHANGE] ?: defaults.remoteChangeEnabled
            )
        }.getOrDefault(defaults)
        val interval = attempt {
            this[SYNC_INTERVAL_MINUTES]?.let { SyncInterval(it) }
        }.getOrNull() ?: SyncInterval.DEFAULT
        return AppSettings(notifications, interval)
    }

    private companion object {
        val INTAKE_REMINDERS = booleanPreferencesKey("intake_reminders_enabled")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val EXPIRY_SOURCE_REMINDERS = booleanPreferencesKey("expiry_source_reminders_enabled")
        val COVERAGE_THRESHOLD_DAYS = longPreferencesKey("coverage_threshold_days")
        val DIGEST_ENABLED = booleanPreferencesKey("digest_enabled")
        val DIGEST_AT_SECOND = intPreferencesKey("digest_at_second_of_day")
        val REMOTE_CHANGE = booleanPreferencesKey("remote_change_enabled")
        val SYNC_INTERVAL_MINUTES = longPreferencesKey("sync_interval_minutes")
    }
}
