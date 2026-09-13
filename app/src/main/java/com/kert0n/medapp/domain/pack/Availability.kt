package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import kotlin.uuid.Uuid

/**
 * Сколько доступно мне по каждой пачке — вход расчётов препарата курса (PLAN D5). Расклад
 * полный: у каждой пачки, о которой спрашивают, число есть, и спрашивать о пачке, которой в
 * раскладе нет, — ошибка вызывающего, а не состояние запаса.
 */
class Availability(availableToMe: Map<Uuid, Quantity>) {

    /**
     * Своя копия, а не переданная карта: расклад, посчитанный один раз, не должен меняться вслед
     * за тем, кто его собрал. Обёрткой-`value class` тут не обойтись — она хранит ту же ссылку.
     */
    private val availableToMe: Map<Uuid, Quantity> = availableToMe.toMap()

    fun of(pkg: PackageRef): Quantity =
        requireNotNull(availableToMe[pkg.id]) { "расклад не называет пачку ${pkg.id}" }

    /** Сколько целых доз даёт пачка. */
    fun dosesOf(pkg: PackageRef, dose: Dose): Doses = of(pkg).dosesIn(dose)

    /**
     * Тот же расклад, где у [pkg] — [quantity]: так курс зажимается под число, которое станет
     * доступным после действия человека, ещё не записанного или ещё не подтверждённого полкой.
     */
    fun with(pkg: PackageRef, quantity: Quantity): Availability =
        Availability(availableToMe + (pkg.id to quantity))

    companion object {

        /** Расклад из посчитанной доступности. */
        fun from(packages: List<PackageAvailability>): Availability =
            Availability(packages.associate { it.packageId to it.availableToMe })
    }
}
