package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.FormSuggestion
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.value.VocabularyResolver
import javax.inject.Inject

/**
 * «Честный знак» как исполнитель порта домена (PLAN H5, H1): ответ переводится в предложение
 * полей, коды и форма ответа за границу сети не уходят. Форма сопоставляется со словарём по
 * снимку: словарь только растёт, и дочитывать его ради подсказки незачем. Отсутствующее не
 * придумывается: чего в ответе нет, того нет и в предложении.
 */
class CrptPackageCodes @Inject constructor(
    private val api: CrptApi,
    private val vocabulary: VocabularyResolver
) : PackageCodes {

    override suspend fun lookup(code: DataMatrixCode): PackageCodes.Lookup = when (val check = api.check(code)) {
        is CrptCheck.Body -> PackageCodes.Lookup.Found(check.dto.toSuggestion(vocabulary.snapshot()))
        CrptCheck.NotFound -> PackageCodes.Lookup.NotFound
        is CrptCheck.Unavailable -> PackageCodes.Lookup.Unavailable(check.reason)
    }
}

/**
 * Категории, которые «Честный знак» считает лекарствами и близким к ним (H5); остальное —
 * предупреждение «это не лекарство».
 */
internal val MEDICINE_CATEGORIES = setOf("drugs", "bio", "antiseptic")

internal fun CrptCheckNetworkDTO.toSuggestion(words: Vocabulary): PackageSuggestion {
    val attributes = attributes
    val pharmacy = pharmacy
    val formText = pharmacy?.form.orNullIfBlank() ?: attributes[FORM_LABEL]
    return PackageSuggestion(
        name = productName.orNullIfBlank() ?: pharmacy?.title.orNullIfBlank(),
        form = formText?.let { FormSuggestion.of(words.formsNamed(it)) } ?: FormSuggestion.None,
        manufacturer = attributes.byLabel(MANUFACTURER_LABELS),
        country = attributes.byLabel(COUNTRY_LABELS),
        expiresOn = expiresOn(),
        activeSubstance = pharmacy?.activeSubstance.orNullIfBlank(),
        dosageText = pharmacy?.dosage.orNullIfBlank() ?: attributes[DOSAGE_LABEL],
        quantityText = pharmacy?.quantity.orNullIfBlank() ?: QUANTITY_LABELS.firstNotNullOfOrNull { attributes[it] },
        isMedicine = category?.trim()?.lowercase() in MEDICINE_CATEGORIES
    )
}

/**
 * Метки атрибутов — наблюдаемые, а не документированные (H5): «Форма выпуска», «Объём / Масса
 * единицы потребления», «Количество единиц потребления», «Объём» видел референс; производитель и
 * страна — по живому ответу пробы, до него берётся метка, содержащая корень слова.
 */
private const val FORM_LABEL = "Форма выпуска"
private const val DOSAGE_LABEL = "Объём / Масса единицы потребления"
private val QUANTITY_LABELS = listOf("Количество единиц потребления", "Объём")
private val MANUFACTURER_LABELS = listOf("производител", "изготовител")
private val COUNTRY_LABELS = listOf("страна")

private fun Map<String, String>.byLabel(roots: List<String>): String? =
    entries.firstOrNull { (label, _) -> roots.any { label.lowercase().contains(it) } }?.value

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
