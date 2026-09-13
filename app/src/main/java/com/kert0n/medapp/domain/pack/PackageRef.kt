package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import kotlin.uuid.Uuid

/**
 * Ссылка на пачку из чужого агрегата — честный список того, что курсу и приёму нужно о ней
 * знать: тождество, имя, единица и форма. Ни остатка, ни места, ни состояний: где коробка
 * лежит и есть ли она вообще, спрашивают у живой [Package] в транзакции, которая её читает, а
 * ссылка переживает коробку — за неё держится история приёмов (PLAN D6).
 *
 * Переходов у ссылки нет: списать или переименовать пачку через копию, лежащую внутри курса,
 * нечем. Равенство по [id]: ссылка указывает на вещь, и та же пачка с новым именем — та же ссылка.
 */
class PackageRef(
    val id: Uuid,
    val name: String,
    val unit: QuantityUnit,
    val form: DosageForm?
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PackageRef && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "PackageRef(id=$id, name=$name, unit=$unit)"
}
