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
 * показывает его отдельно. [holdingCourseId] — идущий курс, которому коробка назначена (другому
 * её не отдать); [lastUsedAt] — момент самого позднего моего приёма из неё, курсового или
 * разового; приёмов не было — `null`. Обе вещи знает не коробка, а те, кто её держит и брал,
 * и приносит их тот, кто читал (PLAN D4).
 */
data class PackageProjection(
    val id: Uuid,
    /** Чем коробка входит в чужой агрегат: имя, единица и форма — то, о чём его спрашивают. */
    val ref: PackageRef,
    val medKit: MedKitRef,
    val facts: PackageFacts,
    val quantity: Quantity,
    val addedAt: Instant,
    val templateId: Uuid?,
    val claims: Claims?,
    val availability: PackageAvailability,
    val hasUnconfirmedChanges: Boolean,
    val holdingCourseId: Uuid?,
    val lastUsedAt: Instant?,
    val status: PackageStatus = PackageStatus.ACTIVE
) {
    init {
        require(availability.packageId == id) { "доступность принадлежит своей пачке" }
    }

    val name: String get() = facts.name
}
