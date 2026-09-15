package com.kert0n.medapp.storage.value

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.value.Money
import java.math.BigDecimal
import java.util.Currency

/** Цена хранится по тому же правилу, что количество: десятичная строка и код валюты рядом. */
fun Money.toStorageAmount(): String = amount.toPlainString()

fun Money.toStorageCurrency(): String = currencyCode

/**
 * Цена из колонок. **Непонятный код валюты значит «цены нет», а не «коробки нет»**: одна
 * порченая строка иначе роняла бы всё чтение — карточку, полку, все лекарства (issue #40).
 * На запись такой код не попадает — `Money` держит `Currency`, — поэтому единственный путь к нему
 * это сама база после правки схемы на месте или чужой записи.
 */
fun storedMoney(amount: String, currencyCode: String): Money? =
    attempt { Currency.getInstance(currencyCode) }.getOrNull()?.let { Money(BigDecimal(amount), it) }
