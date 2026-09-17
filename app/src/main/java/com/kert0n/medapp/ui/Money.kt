package com.kert0n.medapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import java.util.Currency

/**
 * Цена словами: сумма и знак валюты — по нему человек её и узнаёт; незнакомый код остаётся кодом.
 *
 * Живёт общим местом, а не при карточке коробки: цену пишет и карточка, и сводка отчётов, — а
 * написание у них обязано быть одним, как у [words] количества.
 */
@Composable
internal fun MoneyPresentationDTO.text(): String = stringResource(
    R.string.pack_price_value,
    amount,
    attempt { Currency.getInstance(currencyCode).symbol }.getOrDefault(currencyCode)
)
