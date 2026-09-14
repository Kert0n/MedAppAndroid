package com.kert0n.medapp.storage.notification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.Instant

/**
 * Строки обязательств. Запросы здесь ничего не решают: они отдают то, о чём спросили, и пишут то,
 * что дали. Наступило ли обязательство, к чему будить и пора ли забыть — знает сама сущность
 * (PLAN D8, C1).
 */
@Dao
interface ReminderDao {

    /** Состояние целиком: его посчитала сущность, и спорить с ним запросу нечем. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(reminders: Collection<ReminderStorageEntity>)

    @Query("SELECT * FROM reminders WHERE `key` = :key")
    suspend fun find(key: String): ReminderStorageEntity?

    @Query("SELECT * FROM reminders WHERE `key` IN (:keys)")
    suspend fun findAll(keys: Collection<String>): List<ReminderStorageEntity>

    /** Всё невыполненное этим способом доставки — в порядке срока. */
    @Query("SELECT * FROM reminders WHERE state = 'DUE' AND delivery = :delivery ORDER BY due_at")
    suspend fun awaiting(delivery: String): List<ReminderStorageEntity>

    @Query("SELECT * FROM reminders WHERE state = 'WITHDRAWN'")
    suspend fun withdrawn(): List<ReminderStorageEntity>

    /**
     * Грубый отбор старых строк: решает, пора ли забыть, сама сущность — запрос только сужает, а
     * не судит.
     */
    @Query("SELECT * FROM reminders WHERE COALESCE(shown_at, due_at) < :before")
    suspend fun olderThan(before: Instant): List<ReminderStorageEntity>

    @Query("SELECT * FROM reminders WHERE kind IN (:kinds)")
    suspend fun ofKinds(kinds: Collection<String>): List<ReminderStorageEntity>

    @Query("DELETE FROM reminders WHERE `key` IN (:keys)")
    suspend fun deleteAll(keys: Collection<String>)
}
