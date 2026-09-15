package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.time.ClockShifts
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Часы, которые никто не переводит. Поток незавершающийся: `Today` ждёт первого сигнала
 * `first()`-ом, и завершённый пустой поток уронил бы его вместо того, чтобы молчать.
 */
object QuietClock : ClockShifts {
    override val signals = MutableSharedFlow<Unit>()
}
