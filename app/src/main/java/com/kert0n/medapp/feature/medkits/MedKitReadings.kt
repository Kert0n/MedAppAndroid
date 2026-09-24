package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.medkit.MedKitProjection
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface MedKitReadings {

    /**
     * Потоки несут проекции — величины для экрана; сущность отдаёт `find` в транзакции сценария
     * (PLAN H1). Вместе с полкой приходит её содержимое — сколько коробок и сколько просрочено на
     * [today]: день приходит аргументом, потому что база часов не читает (PLAN D2). Полки и их
     * содержимое читаются одним снимком, и на весь список содержимое считается одним запросом.
     */
    fun observeAll(today: LocalDate): Flow<List<MedKitProjection>>

    fun observe(id: Uuid, today: LocalDate): Flow<MedKitProjection?>
}
