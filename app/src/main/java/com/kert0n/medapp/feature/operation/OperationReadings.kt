package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.feature.operation.OutstandingOperation
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface OperationReadings {

    /**
     * Что ещё касается человека **глазами экрана** (PLAN H3 №28): о чём шла речь, что с ней не так, когда придём
     * снова и что можно пересчитать. Форма своя, потому что очередь экрану не видна вовсе, а её
     * типы не могут ехать в представление даже полным именем (`LayerBoundariesTest`). Имена вещей
     * читаются здесь же — команда несёт только тождество.
     */
    fun observeTroubles(): Flow<List<OutstandingOperation>>
}
