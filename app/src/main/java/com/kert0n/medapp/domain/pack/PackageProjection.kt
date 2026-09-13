package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.Quantity
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Пачка глазами экрана: её состояние вместе с доступностью — то, что репозиторий отдаёт наружу
 * потоком (PLAN H1). Величина с равенством по содержимому: расход 20 → 19 даёт другое значение,
 * и `StateFlow` его не потеряет. Переходов нет — сущность остаётся в транзакции, которая её
 * прочитала; строит проекцию сама пачка ([Package.projection]).
 *
 * [availability] — оценка с незакрытыми командами очереди поверх, [hasUnconfirmedChanges] —
 * вложено ли в неё незакрытое изменение остатка (PLAN E1); [quantity] — подтверждённое.
 * [status] — решение о самой коробке, которое ещё не подтвердили: вопрос не о числе, и экран
 * показывает его отдельно.
 */
data class PackageProjection(
    val id: Uuid,
    val medKit: MedKitRef,
    val facts: PackageFacts,
    val quantity: Quantity,
    val addedAt: Instant,
    val templateId: Uuid?,
    val claims: Claims?,
    val availability: PackageAvailability,
    val hasUnconfirmedChanges: Boolean,
    val status: PackageStatus = PackageStatus.ACTIVE
) {
    init {
        require(availability.packageId == id) { "доступность принадлежит своей пачке" }
    }

    val name: String get() = facts.name
}
