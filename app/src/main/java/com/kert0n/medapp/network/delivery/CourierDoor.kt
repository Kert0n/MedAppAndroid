package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.queue.PreparedRequest
import kotlin.uuid.Uuid

/**
 * Дверь курьера в реестр — то, что ему нужно от сервера: отправить замороженный запрос как есть и
 * получить ответ как есть, прочитать снимок пачки и спросить, наша ли аптечка. Узкая, чтобы
 * курьера можно было проверить без HTTP.
 */
interface CourierDoor {

    suspend fun send(request: PreparedRequest): ApiResult<RawResponse>

    suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO>

    /**
     * Видим ли мы аптечку [medKitId] — то есть наша ли она. Сервер отдаёт только те, в которых мы
     * участвуем, поэтому «доступна» и «наша» здесь одно и то же, а `false` — номер занят чужой
     * (PLAN C0).
     */
    suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean>
}
