package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.network.pack.ClaimNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.queue.Expected
import com.kert0n.medapp.queue.Receipt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * Квитанция, разобранная по форме, которую ждало поручение, — ещё на языке провода: снимок здесь
 * — DTO, и в домен его собирает курьер.
 */
internal sealed interface ParsedReceipt {

    data class Snapshot(val snapshot: PackageSnapshotNetworkDTO) : ParsedReceipt

    /** Ноль байтов там, где поручение его ждало: пачки на сервере больше нет. */
    data object Gone : ParsedReceipt

    data class Claim(val claim: ClaimNetworkDTO) : ParsedReceipt

    data object Nothing : ParsedReceipt
}

/**
 * Форму проверяет разбор: ответ не по форме — не «пустая пачка» и не исключение из прохода, а
 * [ApiFailure.Protocol] — исход мог быть применён (PLAN B4, B5). Разбор чистый: записанная
 * квитанция разбирается заново так же, как свежая.
 */
internal fun Expected.read(receipt: Receipt): ApiResult<ParsedReceipt> = when (this) {
    Expected.SNAPSHOT ->
        if (receipt.body.isEmpty()) protocol("пустое тело там, где контракт обещает снимок")
        else decode(receipt.body, PackageSnapshotNetworkDTO.serializer()) { ParsedReceipt.Snapshot(it) }
    Expected.SNAPSHOT_OR_GONE ->
        if (receipt.body.isEmpty()) ApiResult.Success(ParsedReceipt.Gone)
        else decode(receipt.body, PackageSnapshotNetworkDTO.serializer()) { ParsedReceipt.Snapshot(it) }
    Expected.CLAIM ->
        if (receipt.body.isEmpty()) protocol("пустое тело там, где контракт обещает бронь")
        else decode(receipt.body, ClaimNetworkDTO.serializer()) { ParsedReceipt.Claim(it) }
    Expected.NOTHING -> ApiResult.Success(ParsedReceipt.Nothing)
}

private fun <T> decode(body: String, serializer: KSerializer<T>, answer: (T) -> ParsedReceipt): ApiResult<ParsedReceipt> =
    try {
        ApiResult.Success(answer(medAppJson.decodeFromString(serializer, body)))
    } catch (broken: SerializationException) {
        protocol("ответ не разбирается: ${broken.message}")
    } catch (broken: IllegalArgumentException) {
        protocol("ответ вне контракта: ${broken.message}")
    }

private fun protocol(reason: String): ApiResult<ParsedReceipt> = ApiResult.Failure(ApiFailure.Protocol(reason))
