package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.doses

/**
 * Что уже сделано по плану: какие пункты приняты и какие пропущены. Собирает сценарий из
 * приёмов — курс их не хранит, — а курс по нему считает остаток и раскладывает его по календарю:
 * оставшиеся дозы ложатся на следующие **неотвеченные** пункты, а не на следующие N после
 * какого-то момента. Принятые уменьшают потребность, пропущенные — нет, но и своё место в
 * календаре уже заняли: подтверждённые 13:00 при неотвеченных 09:00 оставляют «09:00 и 18:00»,
 * а не «09:00 и 13:00» (PLAN D5). Отменённые пункты сюда не входят: отменяется курс целиком, и
 * считать по нему больше нечего. Величина.
 */
class CourseProgress(
    taken: Set<ScheduledOccurrence> = emptySet(),
    missed: Set<ScheduledOccurrence> = emptySet()
) {
    val taken: Set<ScheduledOccurrence> = taken.toSet()

    val missed: Set<ScheduledOccurrence> = missed.toSet()

    init {
        // Пункт узнаётся по дате и времени: тот же пункт с другим моментом — всё тот же пункт.
        val takenSlots = this.taken.mapTo(HashSet()) { it.slot }
        val missedSlots = this.missed.mapTo(HashSet()) { it.slot }
        require(takenSlots.size == this.taken.size && missedSlots.size == this.missed.size) {
            "пункт отвечен один раз"
        }
        require(takenSlots.none { it in missedSlots }) { "пункт либо принят, либо пропущен" }
    }

    /** Принято по плану — по дозе на пункт: фактическое количество дозой курса не считается. */
    val takenDoses: Doses get() = taken.size.doses

    /** Отвеченные пункты — те, на которые оставшиеся дозы уже не лягут. */
    val answered: Set<ScheduledOccurrence> get() = taken + missed

    override fun equals(other: Any?): Boolean =
        this === other || (other is CourseProgress && taken == other.taken && missed == other.missed)

    override fun hashCode(): Int = 31 * taken.hashCode() + missed.hashCode()

    override fun toString(): String = "CourseProgress(принято ${taken.size}, пропущено ${missed.size})"

    companion object {
        val none: CourseProgress = CourseProgress()

        /** Прогресс лечения — из его пунктов: принятые и пропущенные; план и отмена — не ответ. */
        fun of(intakes: List<CourseIntake>): CourseProgress = CourseProgress(
            taken = intakes.filter { it.status == IntakeStatus.TAKEN }.mapTo(HashSet()) { it.slot },
            missed = intakes.filter { it.status == IntakeStatus.MISSED }.mapTo(HashSet()) { it.slot }
        )
    }
}
