package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.value.Quantity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Доля строки от суммы её полки — свойство **рисунка**, а не отчёта (PLAN H3 «Набор аналитики»):
 * смени полосу на кольцо, и домен не тронется.
 *
 * Считается только внутри одной единицы: полка на то и заведена, что сравнивать таблетки с
 * миллилитрами нельзя. Сумма полки нулём не бывает — полку даёт хотя бы одна непустая строка.
 */
internal fun Quantity.shareOf(total: Quantity): Float =
    amount.divide(total.amount, SHARE_SCALE, RoundingMode.HALF_UP).coerceAtMost(BigDecimal.ONE).toFloat()

private const val SHARE_SCALE = 6
