package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сохранённое состояние упаковки для представления, с равенством по содержимому.
 *
 * Сущность Package сравнивается по id, поэтому её нельзя вкладывать в состояние StateFlow:
 * расход и правка описания окажутся равными старому состоянию. Здесь сущности нет даже внутри
 * вложенных полей. Перечисления — общий доменный словарь, а не изменяемая сущность.
 * quantity — подтверждённый остаток; effective, availableToMe и freeForAnyone — оценка с
 * незакрытыми командами поверх, чужими бронями и своим выделением (PLAN D4, E1).
 *
 * Версии предусловия здесь нет: человеку она ничего не говорит, а экрану состояния синхронизации
 * нужен момент последней сверки, который маппер получает аргументом.
 *
 * expiresOn — величина ExpiryDate, а не дата: «годен до» остаётся включительным до самого экрана.
 * Соседние purchasedOn и openedOn — обычные даты, потому что за ними нет правила. Величины
 * Money и Quantity, наоборот, заменены своими DTO: им нужен формат, а сроку — нет.
 */
data class PackagePresentationDTO(
    val id: Uuid,
    val medKitId: Uuid,
    val name: String,
    val quantity: QuantityPresentationDTO,
    val form: FormPresentationDTO?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val expiresOn: ExpiryDate?,
    val defaultIntakeAmount: QuantityPresentationDTO?,
    val note: String?,
    val price: MoneyPresentationDTO?,
    val purchasedOn: LocalDate?,
    val openedOn: LocalDate?,
    val addedAt: Instant,
    val templateId: Uuid?,
    val claims: ClaimsPresentationDTO?,
    val effective: QuantityPresentationDTO,
    val availableToMe: QuantityPresentationDTO,
    val freeForAnyone: QuantityPresentationDTO,
    val hasUnconfirmedChanges: Boolean,
    val status: PackageStatus,
    val syncedAt: Instant?
)
