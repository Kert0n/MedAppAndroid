package com.kert0n.medapp.queue.medkit

import com.kert0n.medapp.network.medkit.MedKitPostNetworkDTO
import com.kert0n.medapp.network.server.MedAppRoutes
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.queue.PreparedRequest
import java.time.Instant

/**
 * Команда по аптечке как запрос. Предусловий у аптечки нет (PLAN B3), поэтому замораживать здесь
 * нечего, кроме самого пути: удаление и выход повторяются тем же запросом, а «уже нет» закрывает
 * их как исполненные.
 */
fun MedKitSyncCommand.toPreparedRequest(at: Instant): PreparedRequest = when (this) {
    is MedKitSyncCommand.Publish -> PreparedRequest(
        method = "POST",
        path = MedAppRoutes.MED_KITS,
        body = medAppJson.encodeToString(MedKitPostNetworkDTO.serializer(), MedKitPostNetworkDTO(medKitId)),
        preparedAt = at
    )
    is MedKitSyncCommand.Delete -> PreparedRequest(
        method = "DELETE",
        path = MedAppRoutes.medKit(medKitId),
        query = transferTo?.let { mapOf("targetMedKitId" to it.toString()) } ?: emptyMap(),
        preparedAt = at
    )
    is MedKitSyncCommand.Leave -> PreparedRequest(
        method = "DELETE",
        path = MedAppRoutes.membership(medKitId),
        preparedAt = at
    )
}
