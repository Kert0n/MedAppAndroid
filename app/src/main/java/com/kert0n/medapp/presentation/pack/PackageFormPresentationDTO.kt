package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.value.DEFAULT_CURRENCY
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Что напечатано в форме упаковки (PLAN H3 №7, №8). Строки, а не величины: пока человек
 * печатает, «20 » и «03.20» — законные промежуточные состояния, и домен о них знать не должен.
 *
 * Выбранное из готового — объектами словаря ([unit], [form]) и тождеством полки ([medKitId]):
 * напечатать туда несуществующее нельзя по устройству поля, а не по проверке.
 *
 * Срок годности — **строка**: его перепечатывают с коробки как есть, вместе с «03.2027»,
 * которое датой ещё не является. Даты покупки и вскрытия, наоборот, называет календарь, и
 * состояния «13.20» у них не бывает.
 */
data class PackageFormPresentationDTO(
    val medKitId: Uuid? = null,
    val name: String = "",
    val amount: String = "",
    val unit: UnitPresentationDTO? = null,
    val form: FormPresentationDTO? = null,
    val expiresOn: String = "",
    val category: String = "",
    val manufacturer: String = "",
    val country: String = "",
    val description: String = "",
    val hintAmount: String = "",
    val note: String = "",
    val price: String = "",
    /** Валюта записанной цены: правка описания её не меняет; у новой цены — валюта по умолчанию. */
    val currency: String = DEFAULT_CURRENCY.currencyCode,
    val purchasedOn: LocalDate? = null,
    val openedOn: LocalDate? = null
)
