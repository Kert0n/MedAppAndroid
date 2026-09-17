package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Будущий расход — в состояние экрана: строки разложены по единице, и у каждой полки своя сумма.
 *
 * Порядок лечений задаёт домен (от начатых позже) и здесь не меняется; порядок полок — от большей
 * к меньшей по числу строк, чтобы главное стояло первым. Доля строки считается **внутри полки**:
 * сравнивать таблетки с миллилитрами нельзя, а внутри единицы сравнение честное.
 */
fun FutureSpending.toPresentationDTO(horizon: SpendingHorizon, preset: HorizonPreset?): FutureSpendingPresentationDTO =
    FutureSpendingPresentationDTO(
        today = horizon.today,
        until = horizon.until,
        preset = preset,
        groups = episodes.groupBy { it.total.unit }
            .map { (_, episodes) ->
                val total = episodes.map { it.total }.reduce(Quantity::plus)
                ReportGroupPresentationDTO(
                    total = total.toPresentationDTO(),
                    rows = episodes.map { episode ->
                        FutureRowPresentationDTO(
                            courseId = episode.record.id,
                            title = episode.record.title,
                            doses = episode.doses.count,
                            amount = episode.total.toPresentationDTO(),
                            share = episode.total.shareOf(total)
                        )
                    }
                )
            }
            .sortedByDescending { it.rows.size }
    )
