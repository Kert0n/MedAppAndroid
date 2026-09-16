package com.kert0n.medapp.presentation.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Страницы дня (PLAN H3 №12): что у человека назначено и что уже принято.
 *
 * Страница спрашивается **сдвигом** — сегодня, завтра, послезавтра, — и своего дня не заводит:
 * какой сегодня день и когда он сменится, знает `Today`, а что в этот день назначено — `DayPlanning`
 * (C1 «День — общая инфраструктура»). Оттого в полночь та же страница начинает показывать новый
 * день сама, и листать её для этого не надо.
 *
 * Зона приходит вместе с днём: время строки — время в зоне человека, и спрашивать её у экрана
 * значило бы завести второе мнение о том, где он живёт.
 *
 * У каждого сдвига своё чтение, и живёт оно, пока на страницу смотрят: листающий человек держит на
 * виду две страницы разом, и общее состояние показывало бы соседней чужие строки. Чтения хранятся
 * по сдвигу, потому что вернувшийся на вчерашнюю страницу ждёт её же, а не новой подписки.
 */
@HiltViewModel
class DayPlanViewModel @Inject constructor(
    private val today: Today,
    private val planning: DayPlanning
) : ViewModel() {

    private val pages = mutableMapOf<Int, StateFlow<ScreenState<DayPagePresentationDTO>>>()

    /**
     * Страница дня, отстоящего от сегодняшнего на [daysAhead] дней. Спрашивается из вёрстки, то
     * есть с главного потока, — оттого и обычная карта без замка.
     *
     * Отказа у страницы нет: чтения местные. Пока первое значение не пришло — [ScreenState.Loading],
     * а «на этот день ничего не назначено» — это пришедшая пустая страница, а не ожидание.
     */
    fun page(daysAhead: Int): StateFlow<ScreenState<DayPagePresentationDTO>> = pages.getOrPut(daysAhead) {
        combine(today.observe(), planning.observe(daysAhead)) { day, plan ->
            ScreenState.Ready(plan.toPresentationDTO(daysAhead, day.zone))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState.Loading)
    }
}
