package com.kert0n.medapp.platform.time

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Часы устройства перевели или сменили зону — граница суток уехала.
 *
 * Тот же сигнал уже переставляет будильники (`BootAndTimeReceiver`, PLAN D8), и нужен он не
 * только им: всё, что **ждёт** границы дня, ждёт после такого сигнала не того момента. Ждущий
 * не может узнать об этом сам — он спит до старой полуночи, и до неё дата остаётся вчерашней.
 *
 * Сигнал, а не опрос: у события есть свой источник, и спрашивать часы каждую минуту значило бы
 * не знать о нём.
 *
 * Прошедшее без слушателей теряется намеренно: экран, которого никто не открыл, считает день
 * заново при открытии, и хранить для него прошлые переводы часов незачем.
 */
@Singleton
class TimeShifts @Inject constructor() {

    private val shifts = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val signals: Flow<Unit> = shifts

    fun happened() {
        shifts.tryEmit(Unit)
    }
}
