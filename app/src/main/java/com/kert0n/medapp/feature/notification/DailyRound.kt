package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.feature.course.CourseUpkeep
import java.time.Clock
import javax.inject.Inject

/**
 * Проход дня (PLAN D8): календарь приведён в порядок, обязательства сверены — обещано всё, что
 * следует из нынешнего состояния, снято всё, у чего повод исчез. Показывать и будить проход не
 * умеет: это дело [ReminderOutbox], и он проснётся сам — сигнал об изменившейся таблице приходит
 * после коммита.
 *
 * Зовут проход ежедневная задача, загрузка устройства и вход в приложение; повтор безопасен.
 * Обязательства на приёмы здесь не заводятся вовсе: их обещает сам календарь, когда заводит пункт.
 */
class DailyRound @Inject constructor(
    private val upkeep: CourseUpkeep,
    private val reconciliation: NotificationReconciliation,
    private val clock: Clock
) {

    suspend fun run(): Report {
        val kept = upkeep.keepUp()
        val sweep = reconciliation.reconcile(clock.instant(), clock.zone)
        return Report(missed = kept.missed, promised = sweep.promised, withdrawn = sweep.withdrawn)
    }

    /** Сколько пунктов пропущено, сколько обещано по состоянию и сколько снято как беспричинное. */
    data class Report(val missed: Int, val promised: Int, val withdrawn: Int)
}
