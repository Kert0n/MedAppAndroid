package com.kert0n.medapp.network.crpt

import kotlinx.serialization.Serializable

/**
 * Ответ `POST /v2/mobile/check` «Честного знака» — как его наблюдали (проба 2026-09-14, PLAN H5),
 * а не как он задокументирован: документации нет. Всё необязательно, незнакомые ключи не мешают:
 * сменившаяся форма даёт меньше подсказок, а не ошибку — трогать чужой API мы хотим как можно
 * меньше. Из `screen.items[]` читаются аптечный блок (`pharmacyData`), атрибуты (`attrList`) и
 * фишки карточки (`chips`) — страна приходит фишкой `country`, а не атрибутом.
 */
@Serializable
data class CrptCheckNetworkDTO(
    val codeFounded: Boolean = false,
    val category: String? = null,
    val code: String? = null,
    val productName: String? = null,
    val expireDate: Long? = null,
    val screen: CrptScreenNetworkDTO? = null
) {
    /** Аптечный блок — первый из экранных блоков, у которого он есть. */
    val pharmacy: CrptPharmacyNetworkDTO? get() = screen?.items?.firstNotNullOfOrNull { it.pharmacyData }

    /** Фишка карточки по виду — например, страна. */
    fun chip(type: String): String? =
        screen?.items?.asSequence()?.flatMap { it.chips.orEmpty().asSequence() }
            ?.firstOrNull { it.chipType == type }?.value?.trim()?.takeIf { it.isNotEmpty() }

    /** Атрибуты всех блоков одной таблицей `label → value`; повтор метки — первое значение. */
    val attributes: Map<String, String>
        get() = buildMap {
            for (item in screen?.items.orEmpty()) {
                for (attribute in item.attrList.orEmpty()) {
                    val label = attribute.label?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val value = attribute.value?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    putIfAbsent(label, value)
                }
            }
        }
}

@Serializable
data class CrptScreenNetworkDTO(val items: List<CrptScreenItemNetworkDTO>? = null)

@Serializable
data class CrptScreenItemNetworkDTO(
    val itemType: String? = null,
    val pharmacyData: CrptPharmacyNetworkDTO? = null,
    val attrList: List<CrptAttributeNetworkDTO>? = null,
    val chips: List<CrptChipNetworkDTO>? = null
)

@Serializable
data class CrptChipNetworkDTO(val chipType: String? = null, val value: String? = null)

@Serializable
data class CrptPharmacyNetworkDTO(
    val title: String? = null,
    val activeSubstance: String? = null,
    val form: String? = null,
    val dosage: String? = null,
    val quantity: String? = null
)

@Serializable
data class CrptAttributeNetworkDTO(val label: String? = null, val value: String? = null)

/** Тело запроса: код как есть под `{FNC1}` и вид кода — только `datamatrix` (PLAN C1). */
@Serializable
data class CrptCheckRequestNetworkDTO(val code: String, val codeType: String) {
    companion object {
        const val DATA_MATRIX = "datamatrix"
    }
}
