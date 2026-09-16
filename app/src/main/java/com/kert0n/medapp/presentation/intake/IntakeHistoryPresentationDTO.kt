package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * История приёмов (PLAN H3 №19): факты по времени вниз.
 *
 * [subject] называет то, о чём строка **не спрашивали**: пришли с коробки — это лечение (или
 * «разовый приём»), пришли с лечения — коробка. Повторять в каждой строке то, ради чего экран
 * открыли, незачем.
 *
 * Строка живёт дольше вещи, о которой рассказывает: коробка кончилась и выброшена, а приём из неё
 * остаётся — имя и единица берутся из записи о коробке, а не из самой коробки (PLAN D3, D6).
 */
data class IntakeHistoryRowPresentationDTO(
    val id: Uuid,
    val on: LocalDate,
    val at: LocalTime,
    val amount: QuantityPresentationDTO?,
    val subject: String?,
    val state: State
) {

    /** Чем кончился приём: принят, пропущен, отменён вместе с лечением — или он разовый. */
    enum class State { TAKEN, MISSED, CANCELLED, ONE_OFF, PLANNED }
}

/** Вся история одного вопроса: «что я принимал отсюда». */
data class IntakeHistoryUiState(
    val title: String = "",
    val rows: List<IntakeHistoryRowPresentationDTO> = emptyList(),
    val isLoading: Boolean = false
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
