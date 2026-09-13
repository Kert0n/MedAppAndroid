package com.kert0n.medapp.storage.medkit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import java.time.Instant
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.end
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@Dao
interface MedKitDao {

    @Upsert
    suspend fun upsert(medKit: MedKitStorageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(medKit: MedKitStorageEntity)

    /**
     * Снимок трогает только число участников: название и место хранения серверу неизвестны,
     * и точечный `UPDATE` не даёт им пропасть (PLAN F1, E4).
     */
    @Query(
        "UPDATE med_kits SET participant_count = :participantCount, synced_at = :syncedAt " +
            "WHERE id = :id"
    )
    suspend fun applyServerParticipants(id: Uuid, participantCount: Long, syncedAt: Instant)

    @Query("SELECT * FROM med_kits WHERE id = :id")
    fun observe(id: Uuid): Flow<MedKitStorageEntity?>

    @Query("SELECT * FROM med_kits ORDER BY name")
    fun observeAll(): Flow<List<MedKitStorageEntity>>

    @Query("SELECT * FROM med_kits WHERE id = :id")
    suspend fun find(id: Uuid): MedKitStorageEntity?

    /**
     * Полки, о которых сервер знает и по которым нечего ждать: помеченная ждёт ответа на своё
     * решение, и её отсутствие в снимке объясняет он, а не снимок (PLAN E4, E5).
     */
    @Query("SELECT id FROM med_kits WHERE publication = 'PUBLISHED' AND status = 'ACTIVE'")
    suspend fun knownToServer(): List<Uuid>

    /** Все полки, какие у нас есть, — чтобы снимок отличил появившуюся от убранной (PLAN E4). */
    @Query("SELECT id FROM med_kits")
    suspend fun held(): List<Uuid>

    /** Пустую строку аптечки: содержимое к этому моменту либо переехало, либо удалено (PLAN E6). */
    @Query("DELETE FROM med_kits WHERE id = :id")
    suspend fun delete(id: Uuid): Int
}

/**
 * Полки у нас больше нет: вышли сами, или её убрали у всех, или доступ отобрали — для наших данных
 * это одно и то же. Её коробки уходят утратой доступа через ту же дверь, что и всякий конец
 * коробки, а строка полки — следом: полки с содержимым и содержимого без полки не бывает ни на миг
 * (PLAN E6, F5). Почему полки не стало, решает вызывающий: ответ сервера на нашу команду или
 * снимок, который её не назвал.
 */
suspend fun MedKitDao.loseAccess(
    medKitId: Uuid,
    packages: PackageDao,
    courses: CourseDao,
    vocabulary: Vocabulary,
    at: Instant
) {
    for (row in packages.ofMedKit(medKitId)) {
        packages.end(row.toDomain(vocabulary).ended(), courses, vocabulary, at)
    }
    delete(medKitId)
}
