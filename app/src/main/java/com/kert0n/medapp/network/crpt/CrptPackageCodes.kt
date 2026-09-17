package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.scan.ScannedCategory
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.value.VocabularyResolver
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * «Честный знак» как исполнитель порта домена (PLAN H5, H1): ответ переводится в предложение
 * полей, коды и форма ответа за границу сети не уходят. Форма сопоставляется со словарём по
 * снимку: дочитывать его ради подсказки незачем; текст формы из реестра
 * остаётся рядом — словари получены разными путями, и совпадение имён не обещано. Отсутствующее
 * не придумывается: чего в ответе нет, того нет и в предложении.
 */
class CrptPackageCodes @Inject constructor(
    private val api: CrptApi,
    private val vocabulary: VocabularyResolver,
    private val clock: Clock
) : PackageCodes {

    override suspend fun lookup(code: DataMatrixCode): PackageCodes.Lookup = when (val check = api.check(code)) {
        // Сегодня нужно, чтобы отсеять день продажи из будущего: коробку, ещё не проданную,
        // человек в руках не держит. Время берётся часами, а не `LocalDate.now()` (PLAN H1).
        is CrptCheck.Body ->
            PackageCodes.Lookup.Found(check.dto.toSuggestion(vocabulary.snapshot(), LocalDate.now(clock)))
        CrptCheck.NotFound -> PackageCodes.Lookup.NotFound
        is CrptCheck.Unavailable -> PackageCodes.Lookup.Unavailable(check.reason)
    }
}

/**
 * Чем реестр считает товар. Три вида лекарственного он называет своими словами, и слова эти
 * закрыты перечислением: «drugs» и «bio» — это лекарство и добавка, а не текст для человека (H5).
 * Всё остальное — [ScannedCategory.OTHER], и это повод сказать «это не лекарство».
 */
internal fun categoryOf(named: String?): ScannedCategory = when (named?.trim()?.lowercase()) {
    "drugs" -> ScannedCategory.MEDICINE
    "bio" -> ScannedCategory.SUPPLEMENT
    "antiseptic" -> ScannedCategory.ANTISEPTIC
    else -> ScannedCategory.OTHER
}

internal fun CrptCheckNetworkDTO.toSuggestion(words: Vocabulary, today: LocalDate): PackageSuggestion {
    val attributes = attributes
    val pharmacy = pharmacy
    val formText = pharmacy?.form.orNullIfBlank() ?: attributes[FORM_LABEL]
    return PackageSuggestion(
        name = productName.orNullIfBlank() ?: pharmacy?.title.orNullIfBlank(),
        formText = formText,
        form = formText?.let { CrptFormMapper.resolve(it, words) },
        manufacturer = attributes[MANUFACTURER_LABEL] ?: attributes.byLabel(MANUFACTURER_ROOTS),
        country = chip(COUNTRY_CHIP) ?: attributes.byLabel(COUNTRY_ROOTS),
        expiresOn = expiresOn(),
        activeSubstance = pharmacy?.activeSubstance.orNullIfBlank(),
        dosageText = pharmacy?.dosage.orNullIfBlank() ?: attributes[DOSAGE_LABEL],
        quantityText = pharmacy?.quantity.orNullIfBlank() ?: QUANTITY_LABELS.firstNotNullOfOrNull { attributes[it] },
        boughtOn = boughtOn(today),
        category = categoryOf(category)
    )
}

/**
 * Метки — наблюдаемые, а не документированные (H5): «Форма выпуска», «Объём / Масса единицы
 * потребления», «Количество единиц потребления», «Объём» видел референс; «Производитель» и фишку
 * `country` показала проба 2026-09-14 (у лекарства страна — фишкой карточки, а не атрибутом). На
 * случай иной метки у другой категории товара — запасной поиск по корню слова.
 */
private const val FORM_LABEL = "Форма выпуска"
private const val DOSAGE_LABEL = "Объём / Масса единицы потребления"
private val QUANTITY_LABELS = listOf("Количество единиц потребления", "Объём")
private const val MANUFACTURER_LABEL = "Производитель"
private const val COUNTRY_CHIP = "country"
private val MANUFACTURER_ROOTS = listOf("производител", "изготовител")
private val COUNTRY_ROOTS = listOf("страна")

private fun Map<String, String>.byLabel(roots: List<String>): String? =
    entries.firstOrNull { (label, _) -> roots.any { label.lowercase().contains(it) } }?.value

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
