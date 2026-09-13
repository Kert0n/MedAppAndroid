package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.value.Quantity
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сколько доступно по одной пачке (PLAN D4): сколько есть, сколько заявлено чужими, сколько могу
 * взять я и сколько свободно любому. Величина: все поля — числа, и расход 20 → 19 даёт другое
 * значение. Производные — геттеры, поэтому «свободно 5» при нулевом остатке не записать. Строится
 * из пачки: чужие брони берутся у её картины броней, моё выделение приносят курсы.
 *
 * [effective] — число, которое считает очередь: подтверждённый остаток с незакрытыми командами
 * поверх (E1). Число есть всегда: истина по количеству — сервер, а до ответа устройство знает
 * то, что само отправило.
 */
data class PackageAvailability(
    val packageId: Uuid,
    val expiresOn: ExpiryDate?,
    val effective: Quantity,
    val reservedByOthers: Quantity,
    val myAllocation: Quantity
) {

    constructor(
        pkg: Package,
        effective: Quantity,
        myAllocation: Quantity = Quantity.zero(pkg.quantity.unit)
    ) : this(
        packageId = pkg.id,
        expiresOn = pkg.facts.expiresOn,
        effective = effective,
        reservedByOthers = pkg.claims?.let { Quantity(it.reservedByOthers, pkg.quantity.unit) }
            ?: Quantity.zero(pkg.quantity.unit),
        myAllocation = myAllocation
    )

    init {
        val unit = effective.unit
        require(reservedByOthers.unit == unit) { "чужие брони измеряются единицей пачки" }
        require(myAllocation.unit == unit) { "выделение измеряется единицей пачки" }
    }

    /** Сколько могу взять я: вычитается только чужое, свою бронь я заявил сам. */
    val availableToMe: Quantity get() = effective.minusOrZero(reservedByOthers)

    /**
     * Свободно любому: доступное мне без моего выделения. Считается не от суммы броней: моя
     * серверная бронь отстаёт от локального выделения на то, что ещё не уехало (D4).
     */
    val freeForAnyone: Quantity get() = availableToMe.minusOrZero(myAllocation)

    /** Просрочка только помечает: количество не списывается, пачка остаётся источником. */
    fun isExpiredOn(date: LocalDate): Boolean = expiresOn?.isExpiredOn(date) == true

    fun expiresSoonOn(date: LocalDate): Boolean =
        expiresOn?.expiresWithin(date, ExpiryDate.SOON_DAYS) == true
}
