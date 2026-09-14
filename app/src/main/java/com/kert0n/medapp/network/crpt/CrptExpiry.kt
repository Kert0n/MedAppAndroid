package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.pack.ExpiryDate
import java.time.Instant
import java.time.ZoneId

/**
 * Срок годности из ответа — календарная дата **в явно заданной зоне** (PLAN H5): `expireDate`
 * приходит моментом в миллисекундах, и та же полночь по Москве в UTC — ещё вчера. «Честный знак» —
 * российский реестр, и его сутки — московские. Момент до нуля и нечитаемая дата — срока нет.
 */
internal val CRPT_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

internal fun CrptCheckNetworkDTO.expiresOn(): ExpiryDate? {
    val millis = expireDate?.takeIf { it > 0 } ?: return null
    return attempt { ExpiryDate(Instant.ofEpochMilli(millis).atZone(CRPT_ZONE).toLocalDate()) }.getOrNull()
}
