package com.kert0n.medapp.platform.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Часы устройства — в его **нынешней** зоне (PLAN C1 «Часы устройства — в его нынешней зоне»).
 * `Clock.systemDefaultZone()` запоминает зону в момент создания, а часы живут с процессом:
 * переехавший из Москвы во Владивосток человек получал бы сводку по московским девяти до
 * перезапуска. Зона здесь спрашивается у устройства при каждом обращении; момент — системный.
 * Зона курса — своё правило и лежит у курса (D5).
 */
object DeviceClock : Clock() {

    override fun instant(): Instant = Instant.now()

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    /** Часы в названной зоне — уже не устройства: системные, в этой зоне навсегда. */
    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun toString(): String = "DeviceClock(${getZone()})"
}
