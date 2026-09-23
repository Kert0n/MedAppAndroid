package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.ScreenReading
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.kert0n.medapp.presentation.Fresh
import com.kert0n.medapp.presentation.readAfter
import kotlinx.coroutines.flow.map

/**
 * Список аптечек (PLAN H3 №2). Состояние целиком приходит из базы: человек видит не названия, а
 * что внутри — сколько коробок и сколько просрочено, — и по этому решает, куда идти.
 *
 * Отказа у экрана нет: чтение местное, падать ему не на чем. Пока первое значение не пришло —
 * [ScreenState.Loading]: сказать «пусто» раньше значило бы соврать.
 *
 * День берётся у общего [Today], а не считается здесь: просрочка зависит от даты, и в местную
 * полночь — или когда человек переехал — список пересчитает её сам. Свой день завёл бы второе
 * мнение о том, какое сегодня число (PLAN C1 «День — общая инфраструктура»).
 *
 * При входе на экран перечитывается **список полок** — не их содержимое, — и пока сервер не
 * ответил, экран ждёт: человек не должен выбрать полку, из которой его вывели (PLAN E4). Без связи
 * ждать нечего. Содержимое полок обновляет заход.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedKitListViewModel @Inject constructor(
    freshening: Freshening,
    medKits: MedKitStorageRepository,
    today: Today
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    /** Список, прочитанный после перечитывания: до него — ожидание. */
    private val kits = viewModelScope.readAfter(reading, freshening::medKits) {
        today.observe().flatMapLatest { day -> medKits.observeAll(day.date) }
    }

    val state: StateFlow<ScreenState<List<MedKitPresentationDTO>>> = kits
        .map { kits ->
            when (kits) {
                Fresh.Waiting -> ScreenState.Loading
                is Fresh.Read -> ScreenState.Ready(kits.value.map { it.toPresentationDTO() })
            }
        }
        .stateInScreen(viewModelScope, reading, ScreenState.Loading)
}
