package com.kert0n.medapp.domain.medkit

/**
 * Ключ приглашения в полку: один на QR и на текстовый код (PLAN C1 «Приглашение»). Ключ — секрет,
 * он открывает чужую полку, и потому в `toString` не показывается — ни в журнал, ни в отчёт об
 * ошибке он через него не попадёт (PLAN G3).
 *
 * Равенство по значению: два одинаковых кода — одно приглашение.
 */
class InvitationKey(val value: String) {

    init {
        require(value.isNotBlank()) { "ключ приглашения не бывает пустым" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is InvitationKey && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "InvitationKey(***)"
}
