package com.kert0n.medapp.network.crpt

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Срок из ответа — дата в московских сутках, а не в UTC (PLAN H5). Красная проверка: зона UTC —
 * вечер 10-го по Гринвичу дал бы 10-е, а по Москве это уже 11-е.
 */
class CrptExpiryTest {

    @Test
    fun theEpochMillisBecomeAMoscowDate() {
        val lateEvening = Instant.parse("2027-03-10T21:30:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2027, 3, 11), CrptCheckNetworkDTO(expireDate = lateEvening).expiresOn()?.lastDay)
    }

    @Test
    fun aMissingOrSenselessDateIsNoDate() {
        assertNull(CrptCheckNetworkDTO().expiresOn())
        assertNull(CrptCheckNetworkDTO(expireDate = 0).expiresOn())
        assertNull(CrptCheckNetworkDTO(expireDate = -1).expiresOn())
    }
}
