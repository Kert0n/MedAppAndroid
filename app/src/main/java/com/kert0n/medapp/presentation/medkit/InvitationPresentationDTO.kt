package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.Invitation
import com.kert0n.medapp.domain.medkit.InvitationKey
import java.time.LocalTime
import java.time.ZoneId

/**
 * Выданное приглашение глазами экрана: ключ и час, до которого он **примерно** действует. Сервер
 * срока не называет, ключ лежит у него в кэше и может уйти раньше (PLAN B6), поэтому час здесь —
 * оценка, и экран говорит о нём словом «примерно».
 *
 * Ключ остаётся величиной [InvitationKey], а не строкой: он секрет, и его собственный `toString`
 * прячет значение — вместе с ним прячет и этот DTO, попади он в журнал или отчёт об ошибке
 * (PLAN G3).
 */
data class InvitationPresentationDTO(val key: InvitationKey, val expiresAround: LocalTime)

fun Invitation.toPresentationDTO(zone: ZoneId): InvitationPresentationDTO =
    InvitationPresentationDTO(key, expiresAround.atZone(zone).toLocalTime())
