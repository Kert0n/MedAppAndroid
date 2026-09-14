package com.kert0n.medapp.feature.notification

import java.time.LocalTime

/**
 * Проход дня — планировщиком системы (PLAN D8): раз в сутки к желаемому времени сводки, и сразу —
 * когда устройство загрузилось или перевели часы. Точного времени система не обещает.
 */
interface DailySchedule {

    /** Ежедневный проход к [at]; повторный вызов расписание не сдвигает. */
    fun keepDaily(at: LocalTime)

    /** Проход сейчас — при связи или без неё: он локальный. */
    fun runNow()
}
