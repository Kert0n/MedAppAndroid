package com.kert0n.medapp.domain.template

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.QuantityUnit
import kotlin.uuid.Uuid

/**
 * Карточка справочника — описание товарной позиции на сервере, а не пачка (PLAN H5). Человек её не
 * меняет, и приложение держит лишь последнее, что сервер о ней сказал, поэтому это величина.
 *
 * [facts] — то, чем карточка заполняет новую пачку; [unit] — единица, которой пачку предлагают
 * считать. Количества и срока годности в карточке нет: для конкретной коробки их взять неоткуда.
 * Форма и единица, которых словарь не знает, пусты — подсказка оставляет непонятное незаполненным.
 * [nameLat] и [activeSubstance] нужны поиску и подписи, но пачку не заполняют.
 */
data class PackageTemplate(
    val id: Uuid,
    val facts: PackageSharedFacts,
    val unit: QuantityUnit? = null,
    val nameLat: String? = null,
    val activeSubstance: String? = null
)
