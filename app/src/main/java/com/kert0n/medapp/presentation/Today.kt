package com.kert0n.medapp.presentation

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Какой сегодня день — и когда он станет другим. Просрочка, «истекает скоро» и план дня зависят
 * от даты, а не от момента: спросить её один раз при создании `ViewModel` значит оставить
 * открытое приложение во вчера — коробка, просроченная в полночь, до перезапуска выглядела бы
 * годной (наследство разбора #16).
 *
 * Правило живёт здесь, а не в каждом экране: день кончается в **местную** полночь, и зону
 * спрашивают у часов при каждом обороте — переехавший человек получает свой день, а не прежний
 * (PLAN C1 «Часы устройства — в его нынешней зоне»).
 */
@Singleton
class Today @Inject constructor(private val clock: Clock) {

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
            delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1))
        }
    }
}
