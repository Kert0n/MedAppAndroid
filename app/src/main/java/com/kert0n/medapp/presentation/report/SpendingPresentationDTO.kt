package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сколько человек истратил за срок — глазами экрана (PLAN H3 «Набор аналитики», H6).
 *
 * Это **события прошлого**: каждый состоявшийся приём, и внеплановый в их числе. Приём — факт, и
 * расходом он является независимо от доставки; что потом стало с коробкой или лечением, счёта не
 * меняет — потому и полки разные: [episodes] держится за записи лечений, [boxes] — за вечные
 * записи коробок.
 */
data class SpendingPresentationDTO(
    val from: LocalDate,
    val to: LocalDate,
    val today: LocalDate,
    val preset: PeriodPreset?,
    val episodes: List<ReportGroupPresentationDTO<SpentEpisodeRowPresentationDTO>>,
    val boxes: List<ReportGroupPresentationDTO<SpentBoxRowPresentationDTO>>
) {
    val isEmpty: Boolean get() = episodes.isEmpty() && boxes.isEmpty()
}

/**
 * Истраченное по лечению: сколько всего и за сколько приёмов. Нажатие ведёт на карточку лечения —
 * запись эпизода вечна, и закончившееся лечение карточка открывает так же, как идущее (C1).
 */
data class SpentEpisodeRowPresentationDTO(
    val courseId: Uuid,
    val title: String,
    val amount: QuantityPresentationDTO,
    val intakes: Int,
    val share: Float
)

/**
 * Истраченное разовыми приёмами из одной коробки. Нажатия у строки нет: за ней вечная запись, а
 * самой коробки может уже не быть — вести человека в «коробки нет» значит обещать несуществующее
 * (C1). Имя коробки читается и после её конца, потому что хранится записью, а не ссылкой.
 */
data class SpentBoxRowPresentationDTO(
    val name: String,
    val amount: QuantityPresentationDTO,
    val intakes: Int,
    val share: Float
)
