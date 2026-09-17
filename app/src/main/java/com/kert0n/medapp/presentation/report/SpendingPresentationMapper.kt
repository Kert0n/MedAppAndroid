package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.LocalDate

/**
 * Истраченное — в состояние экрана: две полки, внутри каждой — группы по единице со своей суммой.
 *
 * Порядок строк задаёт домен (эпизоды — от начатых позже, коробки — по названию) и здесь не
 * меняется. Доля считается внутри группы: сравнивать таблетки с миллилитрами нельзя.
 */
fun Spending.toPresentationDTO(period: SpendingPeriod, today: LocalDate, preset: PeriodPreset?): SpendingPresentationDTO =
    SpendingPresentationDTO(
        from = period.from,
        to = period.to,
        today = today,
        preset = preset,
        episodes = episodes.byUnit({ it.total }) { row, total ->
            SpentEpisodeRowPresentationDTO(
                courseId = row.record.id,
                title = row.record.title,
                amount = row.total.toPresentationDTO(),
                intakes = row.intakes,
                share = row.total.shareOf(total)
            )
        },
        boxes = packages.byUnit({ it.total }) { row, total ->
            SpentBoxRowPresentationDTO(
                name = row.name,
                amount = row.total.toPresentationDTO(),
                intakes = row.intakes,
                share = row.total.shareOf(total)
            )
        }
    )

/**
 * Строки одной полки — по единицам: сумму складывает сам домен и только внутри единицы. Полки
 * идут от большей к меньшей по числу строк, чтобы главное стояло первым.
 */
private fun <T, R> List<T>.byUnit(
    amount: (T) -> Quantity,
    row: (T, Quantity) -> R
): List<ReportGroupPresentationDTO<R>> = groupBy { amount(it).unit }
    .map { (_, rows) ->
        val total = rows.map(amount).reduce(Quantity::plus)
        ReportGroupPresentationDTO(total.toPresentationDTO(), rows.map { row(it, total) })
    }
    .sortedByDescending { it.rows.size }
