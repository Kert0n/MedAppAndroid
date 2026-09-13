package com.kert0n.medapp.domain.medkit

import java.time.Duration
import java.time.Instant

/**
 * Выданное приглашение в полку: ключ и **оценка** того, до какого момента он действует. Сервер
 * срока не называет, а ключ лежит у него в кэше и может уйти раньше (PLAN B6), поэтому срок —
 * честная оценка от момента выдачи, и экран показывает её оценкой, рядом с «обновить код».
 *
 * Срок один на QR и на текст: ключ у них общий (C1 «Приглашение»).
 */
data class Invitation(val key: InvitationKey, val issuedAt: Instant, val term: Duration) {

    init {
        require(!term.isNegative && !term.isZero) { "приглашение действует какое-то время" }
    }

    /** Примерно до этого момента ключ открывает полку — если сервер не забыл его раньше. */
    val expiresAround: Instant get() = issuedAt.plus(term)
}
