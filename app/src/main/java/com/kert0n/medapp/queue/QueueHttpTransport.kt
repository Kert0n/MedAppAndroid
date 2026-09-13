package com.kert0n.medapp.queue

import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Очередь ходит к серверу через тот же клиент, что и всё остальное: пропуск, лог, таймауты.
 * Адаптер лежит в очереди: он знает её запрос, а сеть про очередь не знает (PLAN H1).
 */
class QueueHttpTransport @Inject constructor(private val api: MedAppApi) : QueueTransport {

    override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> =
        api.send(request.method, request.path, request.query, request.body)

    override suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> =
        api.packageSnapshot(packageId)

    /** `404` — недоступное неотличимо от несуществующего, и для этого вопроса ответ один: не наша. */
    override suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean> = when (val read = api.medKit(medKitId)) {
        is ApiResult.Success -> ApiResult.Success(true)
        is ApiResult.Failure -> if (read.failure == ApiFailure.NotFound) ApiResult.Success(false) else read
    }
}
