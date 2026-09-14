package com.kert0n.medapp.queue

import java.time.Duration

/**
 * Как часто узнавать чужие изменения, пока приложение закрыто (PLAN E4). Величина в **целых
 * минутах** — так о ней говорят человек и экран (H3 №27), и секунд у неё не существует: хранилищу
 * и планировщику терять нечего. Чаще [MIN_MINUTES] планировщик системы не умеет, реже
 * [MAX_MINUTES] недоставленное не успеет уйти за сутки журнала повторов сервера (B6).
 */
data class SyncInterval(val minutes: Long) {

    init {
        require(minutes >= MIN_MINUTES) { "заход реже, чем раз в $MIN_MINUTES минут, планировщик не ставит" }
        require(minutes <= MAX_MINUTES) { "реже, чем раз в $MAX_MINUTES минут, недоставленное не успевает уйти за сутки" }
    }

    val duration: Duration get() = Duration.ofMinutes(minutes)

    companion object {
        const val MIN_MINUTES = 15L
        const val MAX_MINUTES = 8 * 60L
        val DEFAULT = SyncInterval(60)
    }
}
