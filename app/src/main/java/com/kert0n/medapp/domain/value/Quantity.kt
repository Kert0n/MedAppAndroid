package com.kert0n.medapp.domain.value

import java.math.BigDecimal

/**
 * Потолок ответа [Quantity.dosesIn]: число доз — `Int`, а в огромной пачке целых доз бывает больше.
 * Сколько доз назначают, ограничивает назначение (`Prescription.MAX_TOTAL_DOSES`), а не этот предел.
 */
private val MAX_DOSES = BigDecimal(Int.MAX_VALUE)

/**
 * Количество вместе с единицей: величины в разных единицах вместе не считаются. Единица —
 * объект словаря, а не его идентификатор: подставить сюда форму или пачку нечем, и как единица
 * называется, величина знает сама. Ноль допустим — остаток бывает нулевым, а строгая
 * положительность — правило операции. Равенство по значению: сервер отвечает шестью знаками, и
 * `1` равно `1.000000` (PLAN B2).
 */
data class Quantity(val amount: BigDecimal, val unit: QuantityUnit) {

    init {
        requireNonNegativeDecimal(
            amount = amount,
            field = "количество",
            maxScale = SCALE,
            maxIntegerDigits = MAX_INTEGER_DIGITS
        )
    }

    val isZero: Boolean get() = amount.signum() == 0

    operator fun plus(other: Quantity): Quantity {
        requireSameUnit(other)
        return Quantity(amount + other.amount, unit)
    }

    /**
     * Бросает при нехватке: приём пяти таблеток из остатка в три не должен выглядеть успешным,
     * а списание в минус запрещено (PLAN D1, D5).
     */
    operator fun minus(other: Quantity): Quantity {
        requireSameUnit(other)
        require(amount >= other.amount) { "нехватка: $this меньше $other" }
        return Quantity(amount - other.amount, unit)
    }

    /** Для показа доступности, где отрицательное просто не показывается (PLAN D4). */
    fun minusOrZero(other: Quantity): Quantity {
        requireSameUnit(other)
        return if (amount >= other.amount) Quantity(amount - other.amount, unit) else zero(unit)
    }

    /**
     * Умножение только на счётчик приёмов: количество умножается на [Doses], а не на другую
     * величину — произведение таблеток на таблетки смысла не имеет. Неотрицательность проверять
     * не нужно: её обеспечивает сам счётчик.
     */
    operator fun times(doses: Doses): Quantity =
        Quantity(amount * doses.count.toBigDecimal(), unit)

    fun covers(dose: Dose): Boolean {
        requireSameUnit(dose.quantity)
        return amount >= dose.quantity.amount
    }

    /**
     * Сколько целых доз помещается. Именно целых: доза берётся из одной упаковки и между пачками
     * не делится, поэтому по одной таблетке в двух пачках при дозе в две таблетки дают ноль доз,
     * а не одну (PLAN D5). Делить на ноль здесь нечем: [Dose] нулём не бывает.
     */
    fun dosesIn(dose: Dose): Doses {
        requireSameUnit(dose.quantity)
        val whole = amount.divideToIntegralValue(dose.quantity.amount)
        return Doses(if (whole > MAX_DOSES) Int.MAX_VALUE else whole.toInt())
    }

    private fun requireSameUnit(other: Quantity) {
        require(unit == other.unit) {
            "величины в разных единицах не считаются вместе: ${unit.name} и ${other.unit.name}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Quantity) return false
        return unit == other.unit && amount.compareTo(other.amount) == 0
    }

    /**
     * По значению, а не по масштабу: `BigDecimal.hashCode` учитывает `scale`, и `1` с `1.000000`
     * получили бы разные хеши при равных значениях — одна и та же пачка терялась бы в `Map`.
     */
    override fun hashCode(): Int = 31 * unit.hashCode() + amount.stripTrailingZeros().hashCode()

    override fun toString(): String = "${amount.toPlainString()} ${unit.name}"

    companion object {
        /** Разрядность серверного `numeric(19, 6)`: шесть знаков — деление таблетки и капли. */
        const val SCALE = 6

        /** Предел целой части — серверный, принят как продуктовый (C1). */
        const val MAX_INTEGER_DIGITS = 13

        fun zero(unit: QuantityUnit): Quantity = Quantity(BigDecimal.ZERO, unit)
    }
}
