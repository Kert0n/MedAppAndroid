package com.kert0n.medapp.domain.medkit

/**
 * Что лежит на полке, глазами списка: сколько живых коробок и сколько из них просрочено на
 * названный день (PLAN D2, H3 №2). Величина: две полки с одинаковым содержимым для списка
 * одинаковы. Просрочка считается отдельно, а не выводится из числа коробок: по ней человек
 * решает, куда идти первым делом, и видит это раньше, чем откроет полку.
 */
data class MedKitContents(val packages: Int, val expired: Int) {

    init {
        require(packages >= 0) { "коробок не бывает отрицательное число" }
        require(expired in 0..packages) { "просроченных не больше, чем всех коробок" }
    }

    val hasExpired: Boolean get() = expired > 0

    val isEmpty: Boolean get() = packages == 0

    companion object {
        /** Новая полка: коробок в ней ещё нет. */
        val EMPTY = MedKitContents(0, 0)
    }
}
