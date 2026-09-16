package com.kert0n.medapp.platform.time

import com.kert0n.medapp.di.ClockModule
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Часы устройства — в его **нынешней** зоне (PLAN C1 «Часы устройства — в его нынешней зоне»):
 * человек переехал из Москвы во Владивосток, и «09:00» сводки — другой момент. Пока часы
 * приложения фиксировали зону в момент своего создания, они оставались московскими до
 * перезапуска процесса.
 */
class DeviceClockTest {

    private lateinit var before: TimeZone

    @Before
    fun remember() {
        before = TimeZone.getDefault()
    }

    @After
    fun restore() = TimeZone.setDefault(before)

    @Test
    fun theZoneFollowsTheDevice() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        val clock = ClockModule.clock()
        assertEquals(ZoneId.of("Europe/Moscow"), clock.zone)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))

        assertEquals("часы остались в зоне, которая была при их создании", ZoneId.of("Asia/Vladivostok"), clock.zone)
    }
}
