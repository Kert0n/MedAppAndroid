package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid

/**
 * Форма выпуска. Отдельный тип, а не общая «запись словаря» вместе с единицей: обёрток над
 * идентификаторами в домене нет (PLAN D1), и при одном общем типе ничто не помешало бы
 * подставить форму туда, где ждут единицу.
 *
 * Сущность: переименованная форма — та же форма, тождество — [id].
 */
class DosageForm(val id: Uuid, val name: String) {
    init {
        requireText(name, NAME_MAX_LENGTH, "DosageForm.name")
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is DosageForm && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DosageForm(id=$id, name=$name)"

    companion object {
        const val NAME_MAX_LENGTH = 200
    }
}
