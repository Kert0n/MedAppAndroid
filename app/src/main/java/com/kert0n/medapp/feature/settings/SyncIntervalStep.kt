package com.kert0n.medapp.feature.settings

import com.kert0n.medapp.queue.SyncInterval
import kotlin.math.abs

/**
 * Ступени интервала обмена, из которых выбирает человек (PLAN H3 №27). Пределы [SyncInterval]
 * так не нарушить вовсе: каждая ступень — законный интервал, а промежуточные значения человеку
 * не нужны. Правило о пределах остаётся у самой величины — ступень его не повторяет, а строит её.
 * Лежит рядом с [AppSettings], потому что представление величины очереди не видит (PLAN H1).
 */
enum class SyncIntervalStep(val minutes: Long) {
    QUARTER(15), HALF(30), HOUR(60), TWO_HOURS(120), FOUR_HOURS(240), EIGHT_HOURS(480);

    val interval: SyncInterval get() = SyncInterval(minutes)

    companion object {

        /**
         * Ближайшая ступень к записанному интервалу: в хранилище может лежать любое число в
         * пределах величины, а выбирать человек будет из ступеней.
         */
        fun closestTo(interval: SyncInterval): SyncIntervalStep = entries.minBy { abs(it.minutes - interval.minutes) }
    }
}
