package com.kert0n.medapp.queue

import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.RawResponse
import kotlin.uuid.Uuid

/**
 * Что очереди нужно от сервера: отправить замороженный запрос как есть и получить ответ как
 * есть — статус и тело, — прочитать снимок пачки и проверить, наша ли аптечка. Ответ записывается
 * до разбора: разбирает его [Expected.read], чистой функцией, и потому его можно разобрать заново
 * из записи.
 */
interface QueueTransport {

    suspend fun send(request: PreparedRequest): ApiResult<RawResponse>

    suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO>

    /**
     * Видим ли мы аптечку [medKitId] — то есть наша ли она. Сервер отдаёт только те, в которых мы
     * участвуем, поэтому «доступна» и «наша» здесь одно и то же, а `false` — номер занят чужой
     * (PLAN C0). Вопрос узкий: очереди нужно решить, чем кончился занятый номер, а не читать
     * содержимое аптечки.
     */
    suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean>
}
