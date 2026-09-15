package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Список аптечек (PLAN H3 №2). Состояние целиком приходит из базы: человек видит не названия, а
 * что внутри — сколько коробок и сколько просрочено, — и решает по этому, куда идти.
 *
 * Отказа у экрана нет: чтение местное, и падать ему не на чем. Пока первое значение не пришло —
 * [ScreenState.Loading]: сказать «пусто» раньше значило бы соврать.
 *
 * День берётся у [Today], а не спрашивается один раз при создании: просрочка считается на дату, и
 * в полночь список её пересчитывает сам.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedKitListViewModel @Inject constructor(
    medKits: MedKitStorageRepository,
    today: Today
) : ViewModel() {

    val state: StateFlow<ScreenState<List<MedKitPresentationDTO>>> = today.observe()
        .flatMapLatest { day -> medKits.observeAll(day) }
        .map { kits -> ScreenState.Ready(kits.map { it.toPresentationDTO() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState.Loading)
}
