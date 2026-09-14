package com.kert0n.medapp.queue

import java.time.Duration

/**
 * Как часто узнавать чужие изменения, пока приложение закрыто (PLAN E4). Величина с пределами:
 * чаще [MIN] планировщик системы не умеет, реже [MAX] недоставленное не успеет уйти за сутки
 * журнала повторов сервера (B6). Правило живёт здесь, и хранилищу невалидный интервал не передать.
 */
data class SyncInterval(val duration: Duration) {

    init {
        require(duration >= MIN) { "заход реже, чем раз в ${MIN.toMinutes()} минут, планировщик не ставит" }
        require(duration <= MAX) { "реже, чем раз в ${MAX.toHours()} часов, недоставленное не успевает уйти за сутки" }
    }

    companion object {
        val MIN: Duration = Duration.ofMinutes(15)
        val MAX: Duration = Duration.ofHours(8)
        val DEFAULT = SyncInterval(Duration.ofHours(1))
    }
}
