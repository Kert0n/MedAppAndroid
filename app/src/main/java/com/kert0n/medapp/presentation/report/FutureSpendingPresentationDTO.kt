package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сколько человек израсходует до дня — глазами экрана (PLAN H3 «Набор аналитики», H6).
 *
 * Это **обещание расписания**, а не предсказание остатка: нехватка коробок расчёт не режет, и
 * внеплановых приёмов здесь нет вовсе — их никто не обещал. Оба конца срока названы вместе с
 * отчётом: заголовок отчёта и есть «до какого дня», а [today] — тот самый день, от которого
 * считаются пресеты и который очерчивает календарь. Спроси экран часы сам — и мнений о том, какое
 * сегодня число, стало бы два (PLAN C1 «День — общая инфраструктура»).
 *
 * Строки разложены по единице: складывать количества разных единиц нельзя никогда, и группа —
 * единственное место, где сумма имеет смысл.
 */
data class FutureSpendingPresentationDTO(
    val today: LocalDate,
    val until: LocalDate,
    val preset: HorizonPreset?,
    val groups: List<ReportGroupPresentationDTO<FutureRowPresentationDTO>>
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Строка будущего расхода: лечение, сколько доз придётся на срок и сколько это в его единице.
 *
 * Дозы и количество врозь, потому что человек спрашивает и о том, и о другом: «сколько раз» и
 * «сколько таблеток покупать». [courseId] есть всегда — у будущего расхода строка бывает только
 * у идущего лечения, и нажатие ведёт на его карточку (C1).
 */
data class FutureRowPresentationDTO(
    val courseId: Uuid,
    val title: String,
    val doses: Int,
    val amount: QuantityPresentationDTO,
    val share: Float
)

/**
 * Полка отчёта расхода: строки **одной единицы** и их сумма.
 *
 * Сумму складывает сам домен (`Quantity.plus`), и складывает только внутри единицы — оттого
 * группа и заведена: общего «всего» у отчёта не бывает, пока в нём есть и таблетки, и миллилитры.
 */
data class ReportGroupPresentationDTO<T>(val total: QuantityPresentationDTO, val rows: List<T>)
