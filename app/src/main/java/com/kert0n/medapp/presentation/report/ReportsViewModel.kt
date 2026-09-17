package com.kert0n.medapp.presentation.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.storage.report.ReportStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Место «Отчёты» (PLAN H3 «Набор аналитики»): личная статистика человека и ничего больше.
 *
 * Записи здесь нет вовсе — отчёты только отвечают, — поэтому у экрана нет ни сценария, ни
 * сообщения об исходе: всё, что он умеет, это спросить и показать.
 *
 * Отказа у отчёта нет: чтения местные, как у страницы дня. Пока первое значение не пришло —
 * [ScreenState.Loading]; «пусто» это пришедший пустой отчёт, а не ожидание.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    reports: ReportStorageRepository
) : ViewModel() {

    val state: StateFlow<ReportsUiState> = reports.observeStockSummary()
        .map { ReportsUiState(summary = ScreenState.Ready(it.toPresentationDTO())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())
}

/** Что показывает место «Отчёты». Отчёт каждый со своим состоянием: они приходят порознь. */
data class ReportsUiState(
    val summary: ScreenState<StockSummaryPresentationDTO> = ScreenState.Loading
)
