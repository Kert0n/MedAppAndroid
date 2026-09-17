package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.notification.DailySchedule
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SettingsSaved
import com.kert0n.medapp.feature.settings.SettingsStore
import com.kert0n.medapp.queue.SyncInterval
import com.kert0n.medapp.queue.SyncSchedule
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Настройки в памяти: проверкам важно, что записано и что прочитано, а не где лежит. */
class FakeSettingsStore(saved: AppSettings = AppSettings.DEFAULT, var lost: Boolean = false) : SettingsStore {
    private val flow = MutableStateFlow(saved)
    var saved: AppSettings
        get() = flow.value
        set(value) { flow.value = value }
    /** Сколько раз просили записать — в том числе впустую: экран не должен просить дважды. */
    var saves = 0
    override fun observe(): Flow<AppSettings> = flow
    override suspend fun current(): AppSettings = flow.value
    override suspend fun save(settings: AppSettings): SettingsSaved {
        saves++
        if (lost) return SettingsSaved.LOST
        flow.value = settings
        return SettingsSaved.SAVED
    }
}

/** Планировщик регулярного захода, который помнит, с каким интервалом его просили. */
class FakeSyncSchedule : SyncSchedule {
    val kept = ArrayList<SyncInterval>()
    val comeBacks = ArrayList<Instant>()
    override suspend fun keepRegular(interval: SyncInterval) { kept += interval }
    override fun comeBackFor(dueAt: Instant) { comeBacks += dueAt }
}

/** Ежедневный проход, который помнит, к какому времени его просили. */
class FakeDailySchedule : DailySchedule {
    val kept = ArrayList<LocalTime>()
    var runs = 0
    override suspend fun keepDaily(at: LocalTime) { kept += at }
    override fun runNow() { runs++ }
}
