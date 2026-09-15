package com.kert0n.medapp.feature.time

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
 * границу, и о них говорит [ClockShifts].
 */
@Singleton
class Today @Inject constructor(
    private val clock: Clock,
    private val shifts: ClockShifts
) {

    /**
     * Текущий день сразу и каждый следующий — в его местную полночь или когда границу суток
     * увели часы.
     *
     * **Слушают вести всё время, а не в перерывах между днями.** Подписаться на сигнал заново
     * в каждом обороте значит оставить щель между двумя подписками, и весть, пришедшая в неё,
     * пропадает: у [ClockShifts] нет повтора для опоздавших. Постоянный слушатель складывает
     * её в дверь-«последнюю весть» ([Channel.CONFLATED]), и оборот забирает её, когда дойдёт.
     */
    fun observe(): Flow<Day> = channelFlow {
        val moved = Channel<Unit>(Channel.CONFLATED)
        launch { shifts.signals.collect { moved.trySendBlocking(Unit) } }
        while (true) {
            val zone = clock.zone
            val now = clock.instant()
            val today = now.atZone(zone).toLocalDate()
            send(Day(today, zone))
            // Пересчитывается от начала следующего дня, а не «через сутки»: перевод часов и
            // смена зоны иначе увели бы границу дня в сторону и больше не вернули.
            val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
            // Ожидание кончается полуночью — или вестью о том, что полночь теперь другая:
            // переехавший человек иначе досидел бы во вчера до старой границы суток.
            withTimeoutOrNull(Duration.between(now, midnight).toMillis().coerceAtLeast(1)) {
                moved.receive()
            }
        }
    }.distinctUntilChanged()
}

/**
 * День человека — дата **в его зоне**.
 *
 * Зона здесь не украшение: границы дня считаются по ней, и переехавший в полдень получает день
 * с другими началом и концом, даже когда число осталось прежним. Оттого два дня с одним числом,
 * но разными зонами — разные дни, и тот, кто читает «что было за день», обязан перечитать.
 */
data class Day(val date: LocalDate, val zone: ZoneId)
