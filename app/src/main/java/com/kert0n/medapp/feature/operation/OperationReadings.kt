package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.feature.operation.OutstandingOperation
import com.kert0n.medapp.queue.StoredSyncOperation
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface OperationReadings {

    /**
     * Что ещё касается человека — экрану состояния синхронизации (PLAN H3 №28): незакрытые
     * операции (ждут срока, отправляются, ответ записан), отказанные — с причиной, ради которой
     * он решает заново, — и строки, которые нечем прочитать, с причиной. Применённые и утратившие
     * доступ сюда не входят. Сеть не спрашивается.
     */
    fun observeOutstanding(): Flow<List<StoredSyncOperation>>

    /**
     * То же самое **глазами экрана** (PLAN H3 №28): о чём шла речь, что с ней не так, когда придём
     * снова и что можно пересчитать. Форма своя, потому что очередь экрану не видна вовсе, а её
     * типы не могут ехать в представление даже полным именем (`LayerBoundariesTest`). Имена вещей
     * читаются здесь же — команда несёт только тождество.
     */
    fun observeTroubles(): Flow<List<OutstandingOperation>>
}
