package com.kert0n.medapp.storage.intake

import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.queue.intake.IntakeSyncState
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Хранение приёмов. Учёт расхода едет рядом отдельным значением: правила о приёме его не
 * читают, и в доменный тип он не входит (PLAN D6).
 */
interface IntakeStorageRepository {

    /** Поток несёт проекции — величины для экрана; сущности отдают `find`/`ofCourse` в транзакции сценария. */
    fun observeOfCourse(courseId: Uuid): Flow<List<IntakeProjection>>

    /**
     * История коробки — факты приёма из неё, курсовые и разовые вместе, по моменту приёма
     * (PLAN D6). Читается по записи о коробке: кончившаяся коробка историю не теряет, а
     * одноимённая — другая запись, и её приёмы сюда не попадают.
     */
    fun observeOfPackage(packageId: Uuid): Flow<List<IntakeProjection>>

    /** Пункты курса одним чтением — из них сценарий собирает прогресс внутри своей транзакции. */
    suspend fun ofCourse(courseId: Uuid): List<Intake>

    suspend fun find(id: Uuid): Intake?

    suspend fun syncStateOf(id: Uuid): IntakeSyncState?

    /**
     * Запись приёма как есть — например, при приведении к серверному снимку (PLAN E4). Ответ
     * человека идёт через [record]: там условный переход статуса, а здесь его нет.
     */
    suspend fun save(recorded: RecordedIntake)

    /**
     * Материализация окна: повторный проход не заводит второй такой же пункт — тождество даёт
     * курс и исходные дата и время (PLAN F4). Возвращает число заведённых пунктов.
     */
    suspend fun materialise(planned: List<CourseIntake>): Int

    suspend fun plannedBefore(until: Instant): List<CourseIntake>

    /**
     * Убирает плановые пункты курса, которых нет среди [keep] — оставшихся по прогрессу
     * (PLAN D5): поздний ответ по пропущенному сдвинул конец назад, и последний
     * материализованный пункт стал лишним. Он не факт — отвеченные пункты не трогаются никогда.
     * Возвращает число убранных.
     */
    suspend fun prunePlanned(courseId: Uuid, keep: Set<ScheduledOccurrence>): Int

    /**
     * Ответ на приём целиком: условный переход статуса, локальный остаток, пересчитанные
     * выделения курса и учёт — одной транзакцией (PLAN F5); команду расхода ставит служба очереди.
     *
     * `false` означает, что приём уже записан: условный переход не нашёл ожидаемого статуса либо
     * внеплановый приём с тем же тождеством уже заведён, и повтор ничего не списал.
     */
    suspend fun record(outcome: IntakeOutcome): Boolean
}
