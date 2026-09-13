package com.kert0n.medapp.domain.medkit

import kotlin.uuid.Uuid

/**
 * Ссылка на аптечку из чужого агрегата — то, что пачке нужно о ней знать: тождество
 * и кому отвечает лежащее в ней. Переходов у ссылки нет: переименовать или опубликовать аптечку
 * через устаревшую копию, лежащую внутри пачки, нечем — это делает сама [MedKit] в своей
 * транзакции.
 *
 * Равенство по [id]: ссылка указывает на вещь, и та же аптечка, прочитанная позже, — та же
 * ссылка.
 */
class MedKitRef(
    val id: Uuid,
    val publication: MedKit.Publication,
    val status: MedKitStatus
) {
    /** Кому отвечает лежащее в ней — то же правило, что у самой аптечки. */
    val answersToServer: Boolean get() = MedKit.answersToServer(publication, status)

    /** Есть ли куда доставлять её команды — то же правило, что у самой аптечки (PLAN E5). */
    val acceptsCommands: Boolean get() = MedKit.acceptsCommands(publication, status)

    override fun equals(other: Any?): Boolean =
        this === other || (other is MedKitRef && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "MedKitRef(id=$id, publication=$publication, status=$status)"
}
