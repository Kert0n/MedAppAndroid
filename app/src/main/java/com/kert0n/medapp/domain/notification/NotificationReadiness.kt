package com.kert0n.medapp.domain.notification

/**
 * **Можно ли сказать** — один ответ на всё приложение (PLAN C1 «Можно ли сказать — один ответ»).
 *
 * Спрашивают двое: показ — перед тем как говорить, и «День» — чтобы объяснить человеку, почему
 * телефон молчит. Пока каждый спрашивал систему по-своему, заглушённый долгим нажатием канал
 * «Приёмы» молчал, полка росла, а «День» говорил, что всё в порядке (разбор U5). Отвечает владелец
 * каналов — платформа.
 */
interface NotificationReadiness {

    fun now(): Readiness
}

/**
 * Что разрешено сейчас: приложению вообще ([allowed]) и какие разговоры человек заглушил сам
 * ([muted]). Случаи разные и чинятся в разных местах, поэтому различаются.
 */
data class Readiness(val allowed: Boolean, val muted: Set<NotificationChannel> = emptySet()) {

    /** Скажется ли показ в этом канале. */
    fun canSay(channel: NotificationChannel): Boolean = allowed && channel !in muted
}
