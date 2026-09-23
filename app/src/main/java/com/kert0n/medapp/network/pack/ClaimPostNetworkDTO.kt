package com.kert0n.medapp.network.pack

import com.kert0n.medapp.network.server.ResourceVersionNetworkDTO
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Первое заявление брони (`ReservationCreateRequest`). Версия — картины броней; без неё сервер
 * отвечает 428, поэтому она необязательна только по типу: так её отсутствие можно проверить.
 */
@Serializable
data class ClaimPostNetworkDTO(
    @SerialName("drugId") val packageId: Uuid,
    val amount: String,
    val version: ResourceVersionNetworkDTO? = null
) {
    init {
        requirePositiveNetworkAmount(amount, "ClaimPostNetworkDTO.amount")
    }
}
