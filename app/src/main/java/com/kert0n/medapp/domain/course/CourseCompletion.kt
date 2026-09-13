package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import java.time.Instant

/**
 * Политика «последняя доза заканчивает лечение» (PLAN D5): по курсу и прогрессу отвечает,
 * достигнут ли конец, и даёт то же закрытие, что и внешнее, — запись эпизода закрыта как
 * состоявшаяся, будущие пункты отменены. Живёт отдельно от подтверждения приёма, потому что
 * прогресс двигают несколько действий — приём, доза мимо плана, правка числа доз, — и каждое
 * зовёт её, а не повторяет.
 */
class CourseCompletion(private val course: Course, private val progress: CourseProgress) {

    /** Доз впереди не осталось: лечение закончено. */
    val reached: Boolean get() = course.remainingDoses(progress).isNone

    /**
     * Закрытие эпизода: запись — состоявшимся лечением, плановые из [intakes] — отменены.
     * Отвеченные пункты не трогаются: это факты.
     */
    fun close(record: CourseRecord, intakes: List<CourseIntake>, at: Instant): Closing {
        check(reached) { "лечение ещё идёт: осталось ${course.remainingDoses(progress)}" }
        return Closing.of(record, CourseRecord.Outcome.COMPLETED, intakes, at)
    }

    /**
     * Что закрытие меняет: закрытая запись и отменённые пункты — порознь их не бывает. Исходов у
     * закрытия два — лечение состоялось или человек его отменил, — а меняет оно одно и то же.
     */
    class Closing(val record: CourseRecord, cancelled: List<CourseIntake>) {
        val cancelled: List<CourseIntake> = cancelled.toList()

        companion object {

            /**
             * Закрытие эпизода с исходом [outcome]: запись закрыта, плановые из [intakes] отменены.
             * Отвеченные пункты не трогаются: это факты.
             */
            fun of(record: CourseRecord, outcome: CourseRecord.Outcome, intakes: List<CourseIntake>, at: Instant): Closing =
                Closing(
                    record = record.close(outcome, at),
                    cancelled = intakes.filter { it.status == IntakeStatus.PLANNED }.map { it.cancel(at) }
                )
        }
    }
}
