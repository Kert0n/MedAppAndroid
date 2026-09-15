package com.kert0n.medapp.storage.value

import com.kert0n.medapp.domain.value.Money
import java.math.BigDecimal
import java.util.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Цена читается из колонок, а непонятный код валюты значит «цены нет» (issue #40). */
class MoneyStorageMapperTest {

    @Test
    fun storedMoneyReadsBackAsTheSameMoney() {
        val money = Money(BigDecimal("320.50"), Currency.getInstance("RUB"))
        assertEquals(money, storedMoney(money.toStorageAmount(), money.toStorageCurrency()))
    }

    /**
     * Красная проверка: восстанавливать валюту `Currency.getInstance` напрямую — чтение бросает
     * `IllegalArgumentException`, и вместе с ценой падает вся коробка.
     */
    @Test
    fun anUnknownCurrencyCodeMeansNoPrice() {
        assertNull(storedMoney("320", "ZZ"))
    }
}
