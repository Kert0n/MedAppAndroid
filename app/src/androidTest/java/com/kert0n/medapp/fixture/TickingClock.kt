package com.kert0n.medapp.fixture

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Часы, которые двигает проверка: гонка «пока ждали — срок наступил» задаётся точно, а не ожиданием. */
class TickingClock(@Volatile var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
