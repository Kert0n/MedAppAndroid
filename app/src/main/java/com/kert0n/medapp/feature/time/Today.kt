package com.kert0n.medapp.feature.time

import com.kert0n.medapp.platform.time.TimeShifts
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Какой сегодня день — и когда он станет другим.
 *
 * День — **общий**, а не поле экрана (PLAN C1 «День — общая инфраструктура»). Просрочка на
 * списке аптечек, «истекает скоро» и план дня считаются на дату, и посчитанный дважды день к
 * полуночи разъезжается: один экран уже в завтра, другой ещё во вчера. Спросить дату один раз
 * при создании `ViewModel` — та же беда во времени: открытое приложение остаётся во вчера, и
 * коробка, просроченная в полночь, до перезапуска выглядит годной (наследство разбора #16).
 *
 * День кончается в **местную** полночь, и зону спрашивают у часов при каждом обороте:
 * переехавший человек получает свой день, а не прежний (PLAN C1 «Часы устройства — в его
 * нынешней зоне»). Ждать одной лишь полуночи мало — переезд и перевод часов двигают саму
 * границу, и о них говорит [TimeShifts].
 */
@Singleton
class Today @Inject constructor(
    private val clock: Clock,
    /**
     * Значение по умолчанию — ради проверок, которые о переводе часов не спрашивают; графу
     * достаётся общий на приложение [TimeShifts], тот самый, куда пишет `BootAndTimeReceiver`.
     */
    private val shifts: TimeShifts = TimeShifts()
) {

    /** Текущий день сразу и каждый следующий — в его местную полночь. */
    fun observe(): Flow<LocalDate> = flow {
        while (true) {
            val zone = clock.zone
            val now = clock.instant()
            val today = now.atZone(zone).toLocalDate()
            emit(today)
            // Пересчитывается от начала следующего дня, а не «через сутки»: перевод часов и
            // смена зоны иначе увели бы границу дня в сторону и больше не вернули.
            val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
            // Ожидание кончается полуночью — или вестью о том, что полночь теперь другая:
            // переехавший человек иначе досидел бы во вчера до старой границы суток.
            withTimeoutOrNull(Duration.between(now, midnight).toMillis().coerceAtLeast(1)) {
                shifts.signals.first()
            }
        }
    }.distinctUntilChanged()
}
