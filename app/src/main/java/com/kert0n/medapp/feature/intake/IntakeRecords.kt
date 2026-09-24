package com.kert0n.medapp.feature.intake

import androidx.annotation.CheckResult
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Хранение приёмов. Учёт расхода едет рядом отдельным значением: правила о приёме его не
 * читают, и в доменный тип он не входит (PLAN D6).
 */
interface IntakeRecords {

    /** Пункты курса одним чтением — из них сценарий собирает прогресс внутри своей транзакции. */
    suspend fun ofCourse(courseId: Uuid): List<Intake>

    suspend fun find(id: Uuid): Intake?


    /**
     * Запись приёма как есть — например, при приведении к серверному снимку (PLAN E4). Ответ
     * человека идёт через [record]: там условный переход статуса, а здесь его нет.
     */
    suspend fun save(recorded: RecordedIntake)

    /**
     * Материализация окна: повторный проход не заводит второй такой же пункт — тождество даёт
     * курс и исходные дата и время (PLAN F4). Возвращает число заведённых пунктов.
     */
    /** Какие пункты завелись этим проходом: повторная материализация окна их не повторяет. */
    suspend fun materialise(planned: List<CourseIntake>): List<Uuid>

    suspend fun plannedBefore(until: Instant): List<CourseIntake>

    /**
     * Убирает плановые пункты курса, которых нет среди [keep] — оставшихся по прогрессу
     * (PLAN D5): поздний ответ по пропущенному сдвинул конец назад, и последний
     * материализованный пункт стал лишним. Он не факт — отвеченные пункты не трогаются никогда.
     * Возвращает число убранных.
     */
    /** Какие плановые пункты ушли: о них больше не напоминают. */
    suspend fun prunePlanned(courseId: Uuid, keep: Set<ScheduledOccurrence>): List<Uuid>

    /**
     * Ответ на приём целиком: условный переход статуса, локальный остаток, пересчитанные
     * выделения курса и учёт — одной транзакцией (PLAN F5); команду расхода ставит служба очереди.
     *
     * `false` означает, что приём уже записан: условный переход не нашёл ожидаемого статуса либо
     * внеплановый приём с тем же тождеством уже заведён, и повтор ничего не списал.
     */
    @CheckResult
    suspend fun record(outcome: IntakeOutcome): Boolean
}
