package com.kert0n.medapp.network.crpt

import kotlinx.serialization.Serializable

/**
 * Ответ `POST /v2/mobile/check` «Честного знака» — как его наблюдали, а не как он задокументирован:
 * документации нет (PLAN H5). Всё необязательно, незнакомые ключи не мешают: сменившаяся форма
 * даёт меньше подсказок, а не ошибку — трогать чужой API мы хотим как можно меньше.
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
    val attrList: List<CrptAttributeNetworkDTO>? = null
)

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
