package com.kert0n.medapp.presentation.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.feature.time.Day
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.storage.report.ReportStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Место «Отчёты» — личная статистика человека (PLAN H3 «Набор аналитики»).
 *
 * Записи здесь нет вовсе: отчёты отвечают на вопрос и ничего не меняют, — поэтому у экрана нет ни
 * сценария, ни сообщения об исходе. Отказа у чтений тоже нет: они местные, как у страницы дня.
 *
 * **Своего «сегодня» экран не заводит:** день и зону даёт [Today] (PLAN C1 «День — общая
 * инфраструктура»), и потому пресет («Неделя», «Месяц») в полночь сам начинает считаться от нового
 * дня, а названная человеком дата остаётся, где стояла. Спроси день один раз при создании — и
 * открытое с вечера приложение считало бы вчерашним числом.
 *
 * Пока ответ на новый вопрос не пришёл, отчёт показывает ожидание, а не прежний срок: иначе
 * сменивший срок человек читал бы чужие числа под новым заголовком.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportStorageRepository,
    today: Today
) : ViewModel() {

    /**
     * Нынешний день человека — от него считаются пресеты, и им же проверяется своя дата: второго
     * мнения о том, какое сегодня число, у модели нет.
     *
     * Живёт, пока на отчёты смотрят: ожидание суток внутри [Today] — бесконечный оборот, и держать
     * его вечно значило бы будить процесс ради экрана, которого никто не видит. Последний
     * известный день при этом остаётся — на него и смотрят [chooseUntil] и [choosePeriod], когда
     * человек нажимает.
     *
     * Зона идёт вместе с числом: день приёма — календарный день **в зоне человека**, и спрашивать
     * её отдельно значило бы завести второе мнение о том, где он живёт.
     */
    private val day: StateFlow<Day?> = today.observe()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val horizon = MutableStateFlow<HorizonChoice>(HorizonChoice.Preset(HorizonPreset.MONTH))

    private val period = MutableStateFlow<PeriodChoice>(PeriodChoice.Preset(PeriodPreset.MONTH))

    private val summary: Flow<ScreenState<StockSummaryPresentationDTO>> =
        reports.observeStockSummary().map { ScreenState.Ready(it.toPresentationDTO()) }

    private val future: Flow<ScreenState<FutureSpendingPresentationDTO>> =
        combine(day.filterNotNull(), horizon) { day, choice -> SpendingHorizon(day.date, choice.until(day.date)) to choice }
            .distinctUntilChanged()
            .flatMapLatest { (horizon, choice) -> observeFuture(horizon, choice) }

    private val spent: Flow<ScreenState<SpendingPresentationDTO>> =
        combine(day.filterNotNull(), period) { day, choice -> Triple(choice.period(day.date), day, choice) }
            .distinctUntilChanged()
            .flatMapLatest { (period, day, choice) -> observeSpent(period, day, choice) }

    val state: StateFlow<ReportsUiState> = combine(summary, future, spent) { summary, future, spent ->
        ReportsUiState(summary = summary, future = future, spent = spent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    /** Срок одним нажатием: считается от нынешнего дня и незаконным не бывает. */
    fun choose(preset: HorizonPreset) {
        horizon.value = HorizonChoice.Preset(preset)
    }

    /**
     * Свой день из календаря. Дальше трёх месяцев не спрашивают (ТЗ 4.1.1.10.1), и предел держит
     * [SpendingHorizon]: негодная дата **не применяется** — состояние остаётся прежним, а
     * исключение наружу не выходит. В календаре такие дни и не нажимаются; эта проверка — на
     * случай, когда выбор пришёл мимо календаря: полночь, поворот, чужой вызов.
     */
    fun chooseUntil(date: LocalDate) {
        val today = day.value?.date ?: return
        if (attempt { SpendingHorizon(today, date) }.isFailure) return
        horizon.value = HorizonChoice.Until(date)
    }

    /** Срок одним нажатием: считается от нынешнего дня и незаконным не бывает. */
    fun choose(preset: PeriodPreset) {
        period.value = PeriodChoice.Preset(preset)
    }

    /**
     * Свой период из календаря. Длиннее года не спрашивают (ТЗ 4.1.1.10.2), и предел держит
     * [SpendingPeriod]: негодные числа **не применяются**, а исключение наружу не выходит.
     */
    fun choosePeriod(from: LocalDate, to: LocalDate) {
        if (attempt { SpendingPeriod(from, to) }.isFailure) return
        period.value = PeriodChoice.Own(from, to)
    }

    private fun observeFuture(horizon: SpendingHorizon, choice: HorizonChoice): Flow<ScreenState<FutureSpendingPresentationDTO>> =
        reports.observeFutureSpending(horizon)
            .map<_, ScreenState<FutureSpendingPresentationDTO>> {
                ScreenState.Ready(it.toPresentationDTO(horizon, (choice as? HorizonChoice.Preset)?.preset))
            }
            .onStart { emit(ScreenState.Loading) }

    private fun observeSpent(period: SpendingPeriod, day: Day, choice: PeriodChoice): Flow<ScreenState<SpendingPresentationDTO>> =
        reports.observeSpending(period, day.zone)
            .map<_, ScreenState<SpendingPresentationDTO>> {
                ScreenState.Ready(it.toPresentationDTO(period, day.date, (choice as? PeriodChoice.Preset)?.preset))
            }
            .onStart { emit(ScreenState.Loading) }
}

/** Что показывает место «Отчёты». Отчёты приходят порознь, и состояние у каждого своё. */
data class ReportsUiState(
    val summary: ScreenState<StockSummaryPresentationDTO> = ScreenState.Loading,
    val future: ScreenState<FutureSpendingPresentationDTO> = ScreenState.Loading,
    val spent: ScreenState<SpendingPresentationDTO> = ScreenState.Loading
)
