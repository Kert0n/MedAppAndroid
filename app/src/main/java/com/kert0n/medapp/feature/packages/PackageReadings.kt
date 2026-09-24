package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.pack.PackageProjection
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Что экрану нужно от упаковок: проекции — величины, собранные одним чтением в одной транзакции:
 * пачка вместе с доступностью — оценкой количества, чужими бронями и занятым активным курсом
 * (PLAN D4). Объявляет сценарий, исполняет хранение; оценку количества сворачивает очередь.
 */
interface PackageReadings {

    fun observe(id: Uuid): Flow<PackageProjection?>

    /** Список экрана: `today` приходит аргументом, потому что база системных часов не читает. */
    fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>>
}
