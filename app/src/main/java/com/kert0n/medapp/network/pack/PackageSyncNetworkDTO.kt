package com.kert0n.medapp.network.pack

import com.kert0n.medapp.network.server.ResourceVersionNetworkDTO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Офлайн-изменения одной пачки, применяемые сервером атомарно (`DrugSyncRequest`, PLAN B4).
 *
 * `consumed` — **дельта**, а `claim.amount` — **абсолютное** значение моей брони. Отсутствие
 * [claim] значит «бронь не менялась», а не «снять»: снятие — отдельный `DELETE` (PLAN B6).
 */
@Serializable
data class PackageSyncNetworkDTO(
    val consumed: String? = null,
    @SerialName("drugVersion") val packageVersion: ResourceVersionNetworkDTO? = null,
    @SerialName("reservation") val claim: Claim? = null
) {
    init {
        consumed?.let { requirePositiveNetworkAmount(it, "PackageSyncNetworkDTO.consumed") }
        require(consumed == null || packageVersion != null) {
            "офлайн-расход называет версию пачки, на которой сделан"
        }
    }

    /** Моя бронь после офлайн-сессии; без версии сервер берёт текущую картину броней. */
    @Serializable
    data class Claim(
        val amount: String,
        val version: ResourceVersionNetworkDTO? = null
    ) {
        init {
            requirePositiveNetworkAmount(amount, "PackageSyncNetworkDTO.Claim.amount")
        }
    }
}
