package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Разбор формы упаковки. Каждое поле разбирает тот маппер, которому оно принадлежит — количество,
 * срок, цена уже умеют это сами (`QuantityPresentationMapper` и соседи); здесь собирается целое и
 * называется поле, на котором сборка встала.
 *
 * Пределы длин берутся у [PackageSharedFacts] и [PackageFacts] — у типов, которые их держат:
 * повторить их числом здесь значило бы завести вторую правду, которая разойдётся с первой.
 *
 * Пустое необязательное поле — «не указано», а не пустая строка: домен различает эти случаи, и
 * пустая строка сказала бы, что человек назвал пустого производителя.
 */
fun PackageFormPresentationDTO.parsedFacts(
    vocabulary: Vocabulary
): ParsedInput<PackageFacts, PackageFormError> {
    val name = name.trim()
    if (name.isEmpty()) return ParsedInput.Rejected(PackageFormError.NameEmpty)
    if (name.length > PackageSharedFacts.NAME_MAX_LENGTH) {
        return ParsedInput.Rejected(PackageFormError.TooLong(PackageFormError.Field.NAME))
    }

    val category = optional(category, PackageSharedFacts.CATEGORY_MAX_LENGTH, PackageFormError.Field.CATEGORY)
    if (category is ParsedInput.Rejected) return category
    val manufacturer = optional(manufacturer, PackageSharedFacts.MANUFACTURER_MAX_LENGTH, PackageFormError.Field.MANUFACTURER)
    if (manufacturer is ParsedInput.Rejected) return manufacturer
    val country = optional(country, PackageSharedFacts.COUNTRY_MAX_LENGTH, PackageFormError.Field.COUNTRY)
    if (country is ParsedInput.Rejected) return country
    val description = optional(description, PackageSharedFacts.DESCRIPTION_MAX_LENGTH, PackageFormError.Field.DESCRIPTION)
    if (description is ParsedInput.Rejected) return description
    val note = optional(note, PackageFacts.NOTE_MAX_LENGTH, PackageFormError.Field.NOTE)
    if (note is ParsedInput.Rejected) return note

    val form = this.form?.let { chosen ->
        vocabulary.form(chosen.id)
            ?: return ParsedInput.Rejected(PackageFormError.UnknownInVocabulary(PackageFormError.Field.FORM))
    }

    val expiresOn = if (expiresOn.isBlank()) {
        null
    } else {
        when (val parsed = ExpiryDatePresentationDTO(expiresOn).toDomain()) {
            is ParsedInput.Rejected -> return ParsedInput.Rejected(PackageFormError.Expiry(parsed.error))
            is ParsedInput.Parsed -> parsed.value
        }
    }

    val price = if (this.price.isBlank()) {
        null
    } else {
        when (val parsed = MoneyPresentationDTO(this.price, CURRENCY).toDomain()) {
            is ParsedInput.Rejected -> return ParsedInput.Rejected(PackageFormError.Price(parsed.error))
            is ParsedInput.Parsed -> parsed.value
        }
    }

    // Доза-подсказка меряется единицей самой пачки: другой единицы у коробки нет (PLAN D3).
    val hint = if (defaultIntakeAmount.isBlank()) {
        null
    } else {
        val unit = unit ?: return ParsedInput.Rejected(PackageFormError.UnitMissing)
        when (val parsed = QuantityPresentationDTO(defaultIntakeAmount, unit).toDomain(vocabulary)) {
            is ParsedInput.Rejected -> return ParsedInput.Rejected(PackageFormError.Hint(parsed.error))
            is ParsedInput.Parsed ->
                if (parsed.value.isZero) return ParsedInput.Rejected(PackageFormError.HintIsZero)
                else Dose(parsed.value)
        }
    }

    return ParsedInput.Parsed(
        PackageFacts(
            shared = PackageSharedFacts(
                name = name,
                form = form,
                category = category.valueOrNull,
                manufacturer = manufacturer.valueOrNull,
                country = country.valueOrNull,
                description = description.valueOrNull
            ),
            expiresOn = expiresOn,
            defaultIntakeAmount = hint,
            note = note.valueOrNull,
            price = price,
            purchasedOn = purchasedOn,
            openedOn = openedOn
        )
    )
}

/**
 * Сколько лежит в коробке. Отдельно от сведений, потому что заводится это один раз: правка
 * количества — другое действие с другим следом (экран 9, PLAN D3).
 */
fun PackageFormPresentationDTO.parsedQuantity(
    vocabulary: Vocabulary
): ParsedInput<Quantity, PackageFormError> {
    val unit = unit ?: return ParsedInput.Rejected(PackageFormError.UnitMissing)
    return when (val parsed = QuantityPresentationDTO(amount, unit).toDomain(vocabulary)) {
        is ParsedInput.Rejected ->
            if (parsed.error == QuantityPresentationError.UNKNOWN_UNIT) {
                ParsedInput.Rejected(PackageFormError.UnknownInVocabulary(PackageFormError.Field.UNIT))
            } else {
                ParsedInput.Rejected(PackageFormError.Amount(parsed.error))
            }
        // Пустой коробки не бывает: ноль разбор проходит, а заведение отвергает.
        is ParsedInput.Parsed ->
            if (parsed.value.isZero) ParsedInput.Rejected(PackageFormError.AmountIsZero)
            else parsed
    }
}

/** Открытая на правку форма показывает записанное: незаполненное поле остаётся пустым. */
fun PackageProjection.toFormPresentationDTO(): PackageFormPresentationDTO =
    PackageFormPresentationDTO(
        medKitId = medKit.id,
        name = facts.name,
        amount = quantity.amount.toPlainString(),
        unit = quantity.unit.toPresentationDTO(),
        form = facts.form?.toPresentationDTO(),
        category = facts.category.orEmpty(),
        manufacturer = facts.manufacturer.orEmpty(),
        country = facts.country.orEmpty(),
        description = facts.description.orEmpty(),
        expiresOn = facts.expiresOn?.toPresentationDTO()?.text.orEmpty(),
        defaultIntakeAmount = facts.defaultIntakeAmount?.quantity?.amount?.toPlainString().orEmpty(),
        note = facts.note.orEmpty(),
        price = facts.price?.amount?.toPlainString().orEmpty(),
        purchasedOn = facts.purchasedOn,
        openedOn = facts.openedOn
    )

/**
 * Валюта цены. Одна на всё приложение: выбора валюты в продукте нет (C2), а домен её требует —
 * складывать разные валюты он отказывается, и это правильно.
 */
const val CURRENCY: String = "RUB"

private fun optional(
    text: String,
    limit: Int,
    field: PackageFormError.Field
): ParsedInput<String?, PackageFormError> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ParsedInput.Parsed(null)
    if (trimmed.length > limit) return ParsedInput.Rejected(PackageFormError.TooLong(field))
    return ParsedInput.Parsed(trimmed)
}
