package com.kert0n.medapp.feature.time

import kotlinx.coroutines.flow.Flow

/**
 * Часы устройства перевели или сменили зону — граница суток уехала (PLAN D8).
 *
 * Порт, а не механизм: сигнал приносит система, и знает о нём платформа
 * (`platform/time/TimeShifts`, куда пишет `BootAndTimeReceiver`). Ждущему границы дня нужно
 * только одно — узнать, что ждать он стал не того момента.
 */
interface ClockShifts {

    /** Весть о том, что граница суток уехала. Без слушателей теряется: хранить её незачем. */
    val signals: Flow<Unit>
}
