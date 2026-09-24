package com.kert0n.medapp.network.pack

import com.kert0n.medapp.network.server.ResourceVersionNetworkDTO
import kotlinx.serialization.Serializable

/**
 * Новое значение уже заявленной брони (`ReservationPatchRequest`). Снятие брони — не ноль здесь,
 * а отдельный `DELETE`: ноль контракт не принимает (PLAN B6).
 */
@Serializable
data class ClaimPatchNetworkDTO(
    val amount: String,
    val version: ResourceVersionNetworkDTO? = null
) {
    init {
        requirePositiveNetworkAmount(amount, "ClaimPatchNetworkDTO.amount")
    }
}
