package com.kert0n.medapp.storage.intake

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@Dao
interface IntakeDao {

    @Transaction
    @Query("SELECT * FROM intakes WHERE id = :id")
    suspend fun find(id: Uuid): IntakeStorageRow?

    /** Строка без связей — для проверок обвязки, где пачки не нужны. */
    @Query("SELECT * FROM intakes WHERE id = :id")
    suspend fun findEntity(id: Uuid): IntakeStorageEntity?

    @Transaction
    @Query("SELECT * FROM intakes WHERE course_id = :courseId ORDER BY scheduled_at")
    fun observeOfCourse(courseId: Uuid): Flow<List<IntakeStorageRow>>

    @Transaction
    @Query("SELECT * FROM intakes WHERE course_id = :courseId ORDER BY scheduled_at")
    suspend fun ofCourse(courseId: Uuid): List<IntakeStorageRow>

    @Transaction
    @Query(
        "SELECT * FROM intakes WHERE status = 'PLANNED' AND scheduled_at < :until " +
            "ORDER BY scheduled_at"
    )
    suspend fun plannedBefore(until: Instant): List<IntakeStorageRow>

    /**
     * Мои состоявшиеся приёмы с моментом в полуинтервале [from, until) — курсовые и разовые, со
     * ссылками на записи о коробках (PLAN H6). Пропуск и отмена — не приём, и условие по
     * `TAKEN`, а не «не план».
     */
    @Transaction
    @Query(
        "SELECT * FROM intakes WHERE status = 'TAKEN' AND answered_at >= :from AND answered_at < :until " +
            "ORDER BY answered_at"
    )
    suspend fun takenBetween(from: Instant, until: Instant): List<IntakeStorageRow>

    @Upsert
    suspend fun upsert(intake: IntakeStorageEntity)

    /**
     * Материализация окна идемпотентна: пункт узнают по курсу и исходным дате и времени, и
     * повторный проход не заводит второй такой же (PLAN F4).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlannedIfMissing(intakes: List<IntakeStorageEntity>): List<Long>

    /**
     * Внеплановый приём заводится вставкой: строки до него нет, и условному переходу идти не
     * по чему. Повтор узнаётся по тождеству и второй раз ничего не списывает (PLAN D6).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(intake: IntakeStorageEntity): Long

    /**
     * Отмена будущего пункта — такой же условный переход, как ответ: приём, отвеченный между
     * чтением и концом лечения, отменой не затирается (PLAN D6, F2).
     */
    @Query(
        "UPDATE intakes SET status = 'CANCELLED', answered_at = :at " +
            "WHERE id = :id AND status = 'PLANNED'"
    )
    suspend fun cancelIfPlanned(id: Uuid, at: Instant): Int

    /**
     * Идемпотентность ответа: переход идёт условным `UPDATE` по ожидаемому статусу, а не
     * вставкой и не чтением с последующей записью. Ноль изменённых строк означает, что приём
     * уже отвечен, и повтор ничего не списывает (PLAN D6, F2).
     */
    @Query(
        "UPDATE intakes SET status = :to, answered_at = :at, " +
            "taken_package_id = :packageId, " +
            "taken_amount = :amount, unit_id = :unitId, accounting = :accounting, " +
            "operation_id = :operationId " +
            "WHERE id = :id AND status IN (:from)"
    )
    suspend fun answerIfStatusIs(
        id: Uuid,
        from: List<IntakeStatus>,
        to: IntakeStatus,
        at: Instant,
        packageId: Uuid?,
        amount: String?,
        unitId: Uuid,
        accounting: IntakeAccounting,
        operationId: Uuid?
    ): Int

    /**
     * Учёт расхода у приёма, который поставил операцию [operationId]: применён или отказан — что
     * именно, решила очередь. Меняется только ожидающий: учтённое дважды не учитывается.
     */
    @Query("UPDATE intakes SET accounting = :accounting WHERE operation_id = :operationId AND accounting = 'PENDING'")
    suspend fun setAccounting(operationId: Uuid, accounting: IntakeAccounting): Int

    @Query("DELETE FROM intakes WHERE id = :id")
    suspend fun delete(id: Uuid)

    @Query("SELECT * FROM intakes WHERE course_id = :courseId AND status = 'PLANNED'")
    suspend fun plannedOf(courseId: Uuid): List<IntakeStorageEntity>

    /** Убираются только плановые: условие в запросе, а не в вызывающем, — факт не удалится и по ошибке. */
    @Query("DELETE FROM intakes WHERE id IN (:ids) AND status = 'PLANNED'")
    suspend fun deletePlanned(ids: List<Uuid>): Int
}
