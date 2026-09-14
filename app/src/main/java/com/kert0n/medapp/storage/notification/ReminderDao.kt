package com.kert0n.medapp.storage.notification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.Instant

@Dao
interface ReminderDao {

    /**
     * Завести, если ещё не обещали. Повторная сверка существующее **не трогает** — иначе отложенный
     * человеком срок возвращался бы к плановому (PLAN D8).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun raise(reminder: ReminderStorageEntity): Long

    @Query("SELECT * FROM reminders WHERE `key` = :key")
    suspend fun find(key: String): ReminderStorageEntity?

    /** Наступившее и ещё не сказанное — в порядке срока. */
    @Query("SELECT * FROM reminders WHERE state = 'DUE' AND delivery = :delivery AND due_at <= :now ORDER BY due_at")
    suspend fun due(now: Instant, delivery: String): List<ReminderStorageEntity>

    /** Самое раннее невыполненное: к нему и будят процесс. Пусто — будить незачем. */
    @Query("SELECT * FROM reminders WHERE state = 'DUE' AND delivery = :delivery ORDER BY due_at LIMIT 1")
    suspend fun nextDue(delivery: String): ReminderStorageEntity?

    /** Отозванное, но ещё висящее в шторке: погасить и забыть — работа владельца доставки. */
    @Query("SELECT * FROM reminders WHERE state = 'WITHDRAWN'")
    suspend fun withdrawn(): List<ReminderStorageEntity>

    @Query("SELECT * FROM reminders WHERE kind IN (:kinds)")
    suspend fun ofKinds(kinds: Collection<String>): List<ReminderStorageEntity>

    @Query("UPDATE reminders SET due_at = :until, state = 'DUE' WHERE `key` = :key AND state <> 'WITHDRAWN'")
    suspend fun defer(key: String, until: Instant): Int

    /** Первый показ побеждает: момент остаётся тем, когда человек это увидел. */
    @Query("UPDATE reminders SET state = 'SHOWN', shown_at = COALESCE(shown_at, :at) WHERE `key` = :key AND state = 'DUE'")
    suspend fun markShown(key: String, at: Instant): Int

    @Query("UPDATE reminders SET state = 'WITHDRAWN' WHERE `key` IN (:keys) AND state <> 'WITHDRAWN'")
    suspend fun withdraw(keys: Collection<String>): Int

    @Query("DELETE FROM reminders WHERE `key` IN (:keys)")
    suspend fun forget(keys: Collection<String>)

    /** Уборка: сказанное давно больше ни на что не влияет и место занимать не должно. */
    @Query("DELETE FROM reminders WHERE state = 'SHOWN' AND shown_at < :before")
    suspend fun forgetShownBefore(before: Instant): Int
}
