package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO as toVocabularyPresentationDTO
import java.time.Instant

/**
 * Строит состояние экрана из доменной проекции: как та собрана — из каких таблиц и с какой
 * очередью, — представление не знает (PLAN H1).
 *
 * [syncedAt] приходит аргументом, а не из пачки: момент последней сверки принадлежит обвязке
 * синхронизации слоя данных, и домен его не хранит.
 */
fun PackageProjection.toPresentationDTO(syncedAt: Instant? = null): PackagePresentationDTO =
    PackagePresentationDTO(
        id = id,
        medKitId = medKit.id,
        name = facts.name,
        quantity = quantity.toPresentationDTO(),
        form = facts.form?.toVocabularyPresentationDTO(),
        category = facts.category,
        manufacturer = facts.manufacturer,
        country = facts.country,
        description = facts.description,
        expiresOn = facts.expiresOn,
        defaultIntakeAmount = facts.defaultIntakeAmount?.quantity?.toPresentationDTO(),
        note = facts.note,
        price = facts.price?.let {
            MoneyPresentationDTO(it.amount.stripTrailingZeros().toPlainString(), it.currencyCode)
        },
        purchasedOn = facts.purchasedOn,
        openedOn = facts.openedOn,
        addedAt = addedAt,
        templateId = templateId,
        claims = claims?.let {
            ClaimsPresentationDTO(
                total = it.total.stripTrailingZeros().toPlainString(),
                mine = it.mine?.stripTrailingZeros()?.toPlainString()
            )
        },
        effective = availability.effective.toPresentationDTO(),
        availableToMe = availability.availableToMe.toPresentationDTO(),
        freeForAnyone = availability.freeForAnyone.toPresentationDTO(),
        hasUnconfirmedChanges = hasUnconfirmedChanges,
        status = status,
        syncedAt = syncedAt
    )

/** Строки нормализованы: 1 и 1.000000 дают одинаковое состояние экрана. */
private fun Quantity.toPresentationDTO() =
    QuantityPresentationDTO(amount.stripTrailingZeros().toPlainString(), unit.toVocabularyPresentationDTO())
