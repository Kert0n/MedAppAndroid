package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.value.requireOptionalText
import com.kert0n.medapp.domain.value.requireText
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Запись об эпизоде лечения: как человек его назвал, что было назначено, когда началось и чем
 * кончилось. Заводится в момент активации и живёт вечно, поэтому аналитика читает записи и ничего
 * больше — идущее и законченное лечение для неё одной формы (PLAN D5, H6).
 *
 * Сущность, тождество — [id] **эпизода**, общее с планом: ссылка приёма на него не повисает и
 * после того, как план уничтожен, а значит «что принималось по этому поводу» отвечается ею одной.
 * [prescription] — снимок: пока план жив, оно то же самое в обоих; изменение лечения переписывает
 * его той же транзакцией, что и план (PLAN F5). Состав
 * пачек уходит с планом: из какой пачки приняли, записано в приёме.
 */
class CourseRecord(
    val id: Uuid,
    val title: String,
    val note: String? = null,
    val prescription: Prescription,
    val startedAt: Instant,
    val outcome: Outcome? = null,
    val closedAt: Instant? = null
) {

    init {
        requireText(title, TITLE_MAX_LENGTH, "CourseRecord.title")
        requireOptionalText(note, NOTE_MAX_LENGTH, "CourseRecord.note")
        // Исход и момент — одно событие: «закончилось неизвестно когда» и «когда-то кончилось
        // неизвестно чем» это не состояния лечения, а потерянная запись.
        require((outcome == null) == (closedAt == null)) {
            "исход лечения и момент его конца бывают только вместе"
        }
        require(closedAt == null || !closedAt.isBefore(startedAt)) {
            "лечение не кончается раньше, чем началось: $startedAt — $closedAt"
        }
    }

    /** Лечение идёт: план для него ещё существует. */
    val isOpen: Boolean get() = outcome == null

    /** Закрытый эпизод ответов на свой план не принимает: приём после закрытия — внеплановый факт (D6). */
    fun refusesAnswers(): IntakeRejected.Reason? = IntakeRejected.Reason.EPISODE_CLOSED.takeIf { !isOpen }

    /** Как запись видит экран и аналитика: величина, наружу уходит она, а не сущность. */
    fun projection(): CourseRecordProjection =
        CourseRecordProjection(id, title, note, prescription, startedAt, outcome, closedAt)

    /** Название и заметка правятся всегда: это не изменение назначенного лечения (PLAN D5). */
    fun rename(title: String, note: String?): CourseRecord =
        CourseRecord(id, title, note, prescription, startedAt, outcome, closedAt)

    /** Снимок назначения идёт за планом: лечение изменили — запись говорит то же самое (PLAN D5). */
    fun withPrescription(prescription: Prescription): CourseRecord {
        check(isOpen) { "у законченного лечения назначение не правится" }
        return CourseRecord(id, title, note, prescription, startedAt, outcome, closedAt)
    }

    /**
     * Лечение закончилось — календарём или решением человека. План после этого уничтожается, а
     * запись остаётся: состоявшиеся приёмы, их времена и количества не переписываются.
     */
    fun close(outcome: Outcome, at: Instant): CourseRecord {
        check(isOpen) { "лечение уже закончено: ${this.outcome}" }
        return CourseRecord(id, title, note, prescription, startedAt, outcome, at)
    }

    /** Тождество — [id]: переименованная запись остаётся записью того же эпизода. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is CourseRecord && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CourseRecord(id=$id, title=$title, outcome=$outcome)"

    /** Чем кончилось лечение. Третьего исхода нет: идущее лечение — это отсутствие исхода. */
    enum class Outcome {

        /** Календарь закончился, неотвеченных пунктов не осталось. */
        COMPLETED,

        /** Человек отменил лечение (PLAN D5). Изменение лечения эпизод не закрывает. */
        CANCELLED
    }

    companion object {

        /** Имя принадлежит эпизоду, поэтому его предел объявлен здесь; черновик — заготовка. */
        const val TITLE_MAX_LENGTH = 200

        /** Длиннее названия: сюда переписывают запись от врача и «что купить». */
        const val NOTE_MAX_LENGTH = 500
    }
}
