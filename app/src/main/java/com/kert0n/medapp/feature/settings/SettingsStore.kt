package com.kert0n.medapp.feature.settings

import kotlinx.coroutines.flow.Flow

/**
 * Где лежат настройки (PLAN D8). Порт объявлен у вызывающих — сценария изменения, фонового захода,
 * экрана 27, — а исполняет его платформа: как настройки хранятся, здесь не знают. Повреждённое
 * читается умолчаниями и чтением не переписывается; заново файл кладёт только [save] (C1).
 */
interface SettingsStore {

    fun observe(): Flow<AppSettings>

    suspend fun current(): AppSettings

    /** Записать целиком: решение человека — о всём наборе. */
    suspend fun save(settings: AppSettings): SettingsSaved
}

/**
 * Легла ли запись. Случая два, и экран делает разное: [SAVED] — настройки применяются; [LOST] —
 * записать не удалось, и человеку об этом говорят, а прежние настройки действуют дальше.
 */
enum class SettingsSaved { SAVED, LOST }
