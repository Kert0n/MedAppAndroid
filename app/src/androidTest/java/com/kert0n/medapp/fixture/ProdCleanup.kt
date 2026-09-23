package com.kert0n.medapp.fixture

import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import kotlin.uuid.Uuid

/**
 * Синтетическая полка убрана с боевого сервера: её не видит **никто** из [participants]. Удаляют все —
 * вышедший удалить не может, — а верят не ответу на удаление, а отсутствию: `404` не говорит, нет
 * полки или нет доступа, и оставшаяся полка — мусор, который больше никто не найдёт.
 */
suspend fun removeFromProd(medKitId: Uuid, participants: List<MedAppApi>) {
    participants.forEach { it.deleteMedKit(medKitId) }
    val stillSeen = participants.count { api ->
        val seen = api.medKit(medKitId)
        !(seen is ApiResult.Failure && seen.failure == ApiFailure.NotFound)
    }
    if (stillSeen > 0) throw AssertionError("синтетическая полка $medKitId осталась на боевом сервере")
}
