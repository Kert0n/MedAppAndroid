package com.kert0n.medapp.storage.medkit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.end
import com.kert0n.medapp.storage.operation.NamedThing
import java.time.Instant
import java.time.LocalDate
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

    /** Как называются эти вещи: строке очереди нужно имя, а не вся коробка (PLAN H3 №28). */
    @Query("SELECT id, name FROM med_kits WHERE id IN (:ids)")
    suspend fun namesOf(ids: Collection<Uuid>): List<NamedThing>

    @Query("SELECT * FROM med_kits WHERE id = :id")
    fun observe(id: Uuid): Flow<MedKitStorageEntity?>

    @Query("SELECT * FROM med_kits ORDER BY name")
    suspend fun all(): List<MedKitStorageEntity>

    /**
     * Содержимое полок одним чтением: сколько живых коробок и сколько из них просрочено на
     * [today]. Спрашивать про каждую значило бы сто запросов на список из ста (PLAN D2, REQ-055);
     * одна полка — тот же запрос с [medKitId]: определение «просрочена» одно, и второго запроса
     * под него не заводится. Просрочка — строго раньше [today]: «годен до» включительно, как у
     * самой пачки (PLAN D3). Кончившейся коробки в `packages` нет, и она не считается.
     */
    @Query(
        """
        SELECT p.med_kit_id AS med_kit_id,
               COUNT(*) AS packages,
               SUM(CASE WHEN d.expires_on IS NOT NULL AND d.expires_on < :today THEN 1 ELSE 0 END) AS expired
        FROM packages p
        JOIN package_details d ON d.package_id = p.id
        WHERE :medKitId IS NULL OR p.med_kit_id = :medKitId
        GROUP BY p.med_kit_id
        """
    )
    suspend fun contents(today: LocalDate, medKitId: Uuid?): List<MedKitContentsStorageRow>

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
    following: PackageFollowing,
    courses: CourseDao,
    vocabulary: Vocabulary,
    at: Instant
) {
    for (row in packages.ofMedKit(medKitId)) {
        packages.end(row.toDomain(vocabulary).ended(), following, courses, at)
    }
    delete(medKitId)
}
