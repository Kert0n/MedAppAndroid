package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.DEFAULT_CURRENCY
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import kotlin.uuid.Uuid

/** Валюта у цены одна: продукт считает в рублях, а поле валюты человеку не показывают (C1). */
/**
 * Разбор формы упаковки: строки экрана — в то, что примет сценарий.
 *
 * Порядок отказов не случаен. Первым разбирается количество: без него упаковки не бывает, и
 * человеку важнее узнать про него, чем про слишком длинное описание. Пустые необязательные поля
 * значат «не указано», а не пустую строку: пустая означала бы, что человек сказал «нет».
 *
 * Срок в прошлом **принимается** и помечается (ТЗ 4.1.2.3): просроченная коробка — та, что
 * лежит дома, а не ошибка ввода.
 */
fun PackageFormPresentationDTO.parsed(vocabulary: Vocabulary): ParsedInput<PackageDescription, PackageFormError> {
    val medKitId = medKitId ?: return rejected(PackageFormError.MedKitMissing)
    val name = name.trim()
    if (name.isEmpty()) return rejected(PackageFormError.NameEmpty)
    if (name.length > PackageSharedFacts.NAME_MAX_LENGTH) {
        return rejected(PackageFormError.TooLong(PackageFormError.Field.NAME, PackageSharedFacts.NAME_MAX_LENGTH))
    }

    val unit = unit ?: return rejected(PackageFormError.UnitMissing)
    val quantity = when (val parsed = QuantityPresentationDTO(amount, unit).toDomain(vocabulary)) {
        is ParsedInput.Rejected -> return rejected(PackageFormError.Amount(parsed.error))
        is ParsedInput.Parsed -> parsed.value
    }
    if (quantity.isZero) return rejected(PackageFormError.AmountIsZero)

    // Форма выбирается из снимка, но снимок мог устареть: промах — отказ, а не падение.
    val form = form?.let { chosen ->
        vocabulary.form(chosen.id) ?: return rejected(PackageFormError.FormUnknown)
    }

    val expiresOn = expiresOn.trim().ifEmpty { null }?.let { text ->
        when (val parsed = ExpiryDatePresentationDTO(text).toDomain()) {
            is ParsedInput.Rejected -> return rejected(PackageFormError.Expiry(parsed.error))
            is ParsedInput.Parsed -> parsed.value
        }
    }

    val hint = hintAmount.trim().ifEmpty { null }?.let { typed ->
        when (val parsed = QuantityPresentationDTO(typed, unit).toDomain(vocabulary)) {
            is ParsedInput.Rejected -> return rejected(PackageFormError.Hint(parsed.error))
            is ParsedInput.Parsed ->
                if (parsed.value.isZero) return rejected(PackageFormError.HintIsZero) else Dose(parsed.value)
        }
    }

    val price = price.trim().ifEmpty { null }?.let { typed ->
        when (val parsed = MoneyPresentationDTO(typed, currency).toDomain()) {
            is ParsedInput.Rejected -> return rejected(PackageFormError.Price(parsed.error))
            is ParsedInput.Parsed -> parsed.value
        }
    }

    val texts = listOf(
        Triple(category.trim(), PackageSharedFacts.CATEGORY_MAX_LENGTH, PackageFormError.Field.TEXT),
        Triple(manufacturer.trim(), PackageSharedFacts.MANUFACTURER_MAX_LENGTH, PackageFormError.Field.TEXT),
        Triple(country.trim(), PackageSharedFacts.COUNTRY_MAX_LENGTH, PackageFormError.Field.TEXT),
        Triple(description.trim(), PackageSharedFacts.DESCRIPTION_MAX_LENGTH, PackageFormError.Field.TEXT),
        Triple(note.trim(), PackageFacts.NOTE_MAX_LENGTH, PackageFormError.Field.TEXT)
    )
    texts.firstOrNull { (text, limit, _) -> text.length > limit }?.let { (_, limit, field) ->
        return rejected(PackageFormError.TooLong(field, limit))
    }

    return ParsedInput.Parsed(
        PackageDescription(
            medKitId = medKitId,
            quantity = quantity,
            facts = PackageFacts(
                shared = PackageSharedFacts(
                    name = name,
                    form = form,
                    category = category.trim().ifEmpty { null },
                    manufacturer = manufacturer.trim().ifEmpty { null },
                    country = country.trim().ifEmpty { null },
                    description = description.trim().ifEmpty { null }
                ),
                expiresOn = expiresOn,
                defaultIntakeAmount = hint,
                note = note.trim().ifEmpty { null },
                price = price,
                purchasedOn = purchasedOn,
                openedOn = openedOn
            ),
            templateId = templateId
        )
    )
}

/** Записанная коробка — обратно в форму: человек правит то, что видел. */
fun PackageProjection.toFormPresentationDTO(): PackageFormPresentationDTO =
    PackageFormPresentationDTO(
        medKitId = medKit.id,
        name = facts.name,
        amount = quantity.amount.stripTrailingZeros().toPlainString(),
        unit = quantity.unit.toPresentationDTO(),
        form = facts.form?.toPresentationDTO(),
        expiresOn = facts.expiresOn?.toPresentationDTO()?.text.orEmpty(),
        category = facts.category.orEmpty(),
        manufacturer = facts.manufacturer.orEmpty(),
        country = facts.country.orEmpty(),
        description = facts.description.orEmpty(),
        hintAmount = facts.defaultIntakeAmount?.quantity?.amount?.stripTrailingZeros()?.toPlainString().orEmpty(),
        note = facts.note.orEmpty(),
        price = facts.price?.amount?.stripTrailingZeros()?.toPlainString().orEmpty(),
        currency = facts.price?.currencyCode ?: DEFAULT_CURRENCY.currencyCode,
        purchasedOn = facts.purchasedOn,
        openedOn = facts.openedOn
    )

/** Что человек назвал, уже проверенное: это и принимает сценарий. */
data class PackageDescription(
    val medKitId: Uuid,
    val quantity: Quantity,
    val facts: PackageFacts,
    val templateId: Uuid? = null
)

private fun rejected(error: PackageFormError): ParsedInput<PackageDescription, PackageFormError> =
    ParsedInput.Rejected(error)
