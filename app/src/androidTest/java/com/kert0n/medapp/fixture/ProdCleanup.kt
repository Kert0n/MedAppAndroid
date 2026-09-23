package com.kert0n.medapp.fixture

import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import kotlin.uuid.Uuid

/**
 * Синтетические полки убраны с боевого сервера: ни одну из [medKitIds] не видит **никто** из
 * [participants]. Удаляют все — вышедший удалить не может, — а верят не ответу на удаление, а
 * отсутствию: `404` не говорит, нет полки или нет доступа, и оставшаяся полка — мусор, который больше
 * никто не найдёт. Сначала удаляются все полки, потом проверяются все: оставшаяся первая не мешает
 * убрать вторую.
 */
suspend fun removeFromProd(medKitIds: List<Uuid>, participants: List<MedAppApi>) {
    medKitIds.forEach { id -> participants.forEach { it.deleteMedKit(id) } }
    val left = medKitIds.filter { id ->
        participants.any { api ->
            val seen = api.medKit(id)
            !(seen is ApiResult.Failure && seen.failure == ApiFailure.NotFound)
        }
    }
    if (left.isNotEmpty()) throw AssertionError("синтетические полки остались на боевом сервере: $left")
}
