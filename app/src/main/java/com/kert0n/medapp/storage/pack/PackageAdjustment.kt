package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.value.Quantity
import kotlin.uuid.Uuid

/**
 * Изменение остатка или места пачки помимо приёма: пересчёт, утилизация, перенос между аптечками
 * (PLAN F5). Названо действием человека, а не его результатом: «пересчитал и увидел 17», а не
 * «пачка теперь 17».
 *
 * Разница существенна. Результат считается по тому состоянию, которое вызывающий прочитал когда-то
 * раньше, и записывается поверх нынешнего. Переход же применяется к тому состоянию, которое лежит
 * в базе: утилизация не списывает в минус от давно изменившегося числа. Что означает каждый
 * переход, по-прежнему решает домен: хранение только называет действие и записывает результат.
 */
sealed interface PackageAdjustment {

    val packageId: Uuid

    /** «Пересчитал и увидел столько» — замена значения, а не дельта (PLAN E1). */
    data class Recount(override val packageId: Uuid, val actual: Quantity) : PackageAdjustment

    /** Выбросили названное количество; ушедшая в ноль коробка кончается. В минус пачка не списывается. */
    data class Disposal(override val packageId: Uuid, val amount: Quantity) : PackageAdjustment

    /**
     * Перенос в другую аптечку: меняется место, а не остаток. Принимает саму аптечку, а не её
     * идентификатор, — как и переход пачки, который этим переносом и вызывается.
     */
    data class Transfer(override val packageId: Uuid, val target: MedKitRef) : PackageAdjustment

    /**
     * Применяет переход к нынешнему состоянию пачки: что с ней стало, отвечает сама пачка.
     * Хранение называет действие и записывает ответ, но не решает, что действие значит.
     */
    fun applyTo(pack: Package): PackageAfter {
        require(pack.id == packageId) { "переход применяется к своей пачке" }
        return when (this) {
            is Recount -> pack.correctTo(actual)
            is Disposal -> pack.dispose(amount)
            is Transfer -> PackageAfter.Left(pack.moveTo(target))
        }
    }
}
