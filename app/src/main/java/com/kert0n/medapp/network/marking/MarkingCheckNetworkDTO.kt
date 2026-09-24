package com.kert0n.medapp.network.marking

import com.kert0n.medapp.domain.scan.DataMatrixCode
import kotlinx.serialization.Serializable

/**
 * Ответ реестра маркировки на проверку кода — как его наблюдали (проба 2026-09-14, PLAN H5). Всё
 * необязательно, незнакомые ключи не мешают: сменившаяся форма даёт меньше подсказок, а не
 * ошибку — трогать чужой сервис мы хотим как можно меньше. Из `screen.items[]` читаются аптечный блок (`pharmacyData`), атрибуты (`attrList`) и
 * фишки карточки (`chips`) — страна приходит фишкой `country`, а не атрибутом.
 */
@Serializable
data class MarkingCheckNetworkDTO(
    val codeFounded: Boolean = false,
    val category: String? = null,
    val code: String? = null,
    val productName: String? = null,
    val expireDate: Long? = null,
    /** Когда коробку продали: через реестр проходит кассовый чек, и для человека это день покупки. */
    val receiptDate: Long? = null,
    val screen: MarkingScreenNetworkDTO? = null
) {
    /** Аптечный блок — первый из экранных блоков, у которого он есть. */
    val pharmacy: MarkingPharmacyNetworkDTO? get() = screen?.items?.firstNotNullOfOrNull { it.pharmacyData }

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
data class MarkingScreenNetworkDTO(val items: List<MarkingScreenItemNetworkDTO>? = null)

@Serializable
data class MarkingScreenItemNetworkDTO(
    val itemType: String? = null,
    val pharmacyData: MarkingPharmacyNetworkDTO? = null,
    val attrList: List<MarkingAttributeNetworkDTO>? = null,
    val chips: List<MarkingChipNetworkDTO>? = null
)

@Serializable
data class MarkingChipNetworkDTO(val chipType: String? = null, val value: String? = null)

@Serializable
data class MarkingPharmacyNetworkDTO(
    val title: String? = null,
    val activeSubstance: String? = null,
    val form: String? = null,
    val dosage: String? = null,
    val quantity: String? = null
)

@Serializable
data class MarkingAttributeNetworkDTO(val label: String? = null, val value: String? = null)

/**
 * Тело запроса: код как есть под литеральным `{FNC1}` и вид кода — только `datamatrix` (PLAN C1,
 * H5). Префикс — формат чужого сервиса, и кладёт его этот маппер, а не величина: смена его формата
 * правит сетевую границу, а не домен. Правило «байт в байт» держит тест тела запроса.
 */
@Serializable
data class MarkingCheckRequestNetworkDTO(val code: String, val codeType: String) {
    companion object {
        const val DATA_MATRIX = "datamatrix"

        /** Литерал из шести символов, а не управляющий байт — так ждёт реестр. */
        const val FNC1 = "{FNC1}"

        fun of(code: DataMatrixCode): MarkingCheckRequestNetworkDTO = MarkingCheckRequestNetworkDTO(FNC1 + code.text, DATA_MATRIX)
    }
}
