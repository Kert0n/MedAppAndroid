package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import kotlin.uuid.Uuid

/**
 * Подсказка справочника строкой списка (PLAN H3 №7): название и то, по чему её узнают —
 * форма и производитель. Остальное, чем карточка заполняет форму, едет с ней же: выбор строки
 * переносит [category], [country], [description] и [unit] в пустые поля, и второго чтения
 * карточки для этого не нужно.
 */
data class TemplatePresentationDTO(
    val id: Uuid,
    val name: String,
    val form: FormPresentationDTO?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val unit: UnitPresentationDTO?
)

fun PackageTemplate.toPresentationDTO(): TemplatePresentationDTO = TemplatePresentationDTO(
    id = id,
    name = facts.name,
    form = facts.form?.toPresentationDTO(),
    category = facts.category,
    manufacturer = facts.manufacturer,
    country = facts.country,
    description = facts.description,
    unit = unit?.toPresentationDTO()
)
