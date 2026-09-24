package com.kert0n.medapp.network.pack

import com.kert0n.medapp.network.server.ResourceVersionNetworkDTO
import kotlinx.serialization.Serializable

/**
 * Картина броней на пачке (`ReservationsDTO`): сумма всех броней, моя часть и своя версия.
 * Сумма может превышать остаток; моей брони может не быть — тогда `mine` пусто, а не ноль.
 */
@Serializable
data class ClaimsNetworkDTO(
    val total: String,
    val mine: String? = null,
    val version: ResourceVersionNetworkDTO
) {
    init {
        requireNetworkAmount(total, "ClaimsNetworkDTO.total")
        mine?.let { requirePositiveNetworkAmount(it, "ClaimsNetworkDTO.mine") }
    }
}
