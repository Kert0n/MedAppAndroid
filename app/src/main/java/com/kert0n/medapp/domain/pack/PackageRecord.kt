package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Запись о коробке: как называлась, в чём считали, форма и когда появилась. Заводится вместе с
 * пачкой и живёт вечно — за неё держатся приёмы, поэтому история читается и после того, как
 * коробка кончилась или выброшена (PLAN D3, D6). Зеркало записи эпизода:
 * тождество общее с живой пачкой, снимок имени, единицы и формы идёт за ней и переписывается той
 * же транзакцией, что и пачка.
 *
 * Исхода у записи нет: есть ли коробка сейчас, отвечает наличие живой пачки, а истории у коробки
 * нет (D7). Переходов нет тоже: запись меняется только вслед за пачкой ([Package.record]).
 */
class PackageRecord(
    val id: Uuid,
    val name: String,
    val unit: QuantityUnit,
    val form: DosageForm?,
    val addedAt: Instant
) {
    /** Как запись видит чужой агрегат: ссылка без момента появления. */
    val ref: PackageRef get() = PackageRef(id, name, unit, form)

    /** Тождество — [id], общее с живой пачкой: переименованная коробка — та же коробка. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is PackageRecord && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "PackageRecord(id=$id, name=$name, unit=$unit)"
}
