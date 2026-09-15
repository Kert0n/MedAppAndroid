package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Упаковка в том виде, в каком её держит форма: строками, как человек напечатал (PLAN H1). Пока
 * он печатает, в количестве лежит «12,» или пусто, а в сроке — «03.20»; ни одно из этих
 * состояний величиной не является, и пачкой такое состояние не назовёшь.
 *
 * Обязательных полей четыре — аптечка, название, количество и единица (PLAN C1); остальные есть
 * **все до одного** (ТЗ 4.1.1.1), и пустое поле значит «не указано».
 *
 * Аптечка, единица и форма едут выбранными значениями, а не строками: их человек выбирает из
 * списка, и невыразимого состояния у выбора нет. Так же и даты покупки и вскрытия — их называет
 * календарь. Срок годности, наоборот, строка: его перепечатывают с упаковки как есть, вместе с
 * «03.2027», которое датой ещё не является.
 */
data class PackageFormPresentationDTO(
    val medKitId: Uuid? = null,
    val name: String = "",
    val amount: String = "",
    val unit: UnitPresentationDTO? = null,
    val form: FormPresentationDTO? = null,
    val category: String = "",
    val manufacturer: String = "",
    val country: String = "",
    val description: String = "",
    val expiresOn: String = "",
    val defaultIntakeAmount: String = "",
    val note: String = "",
    val price: String = "",
    val purchasedOn: LocalDate? = null,
    val openedOn: LocalDate? = null
)
