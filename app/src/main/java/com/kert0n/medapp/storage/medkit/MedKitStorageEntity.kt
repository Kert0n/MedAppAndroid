package com.kert0n.medapp.storage.medkit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.medkit.MedKitStatus
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Аптечка целиком в одной строке: снимок трогает у неё **только** число участников, а название
 * и место хранения серверу неизвестны и стереться не могут (PLAN F1).
 *
 * `synced_at` — обвязка синхронизации; доменная аптечка её не носит.
 */
@Entity(tableName = "med_kits")
class MedKitStorageEntity(
    @PrimaryKey val id: Uuid,
    val name: String,
    val location: String?,
    val publication: MedKit.Publication,
    @ColumnInfo(name = "participant_count") val participantCount: Long,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "synced_at") val syncedAt: Instant? = null,
    /** Неподтверждённое решение об аптечке; снимок сервера его не переписывает (PLAN E5, E6). */
    @ColumnInfo(defaultValue = "ACTIVE") val status: MedKitStatus = MedKitStatus.ACTIVE
) {
    fun toDomain(): MedKit = MedKit(
        id = id,
        name = name,
        location = location,
        publication = publication,
        participantCount = participantCount,
        createdAt = createdAt,
        status = status
    )

    /** Ссылка для чужого агрегата: пачке и движению от аптечки нужны тождество, публикация и пометка. */
    fun toRef(): MedKitRef = MedKitRef(id, publication, status)
}

fun MedKit.toStorageEntity(syncedAt: Instant? = null): MedKitStorageEntity = MedKitStorageEntity(
    id = id,
    name = name,
    location = location,
    publication = publication,
    participantCount = participantCount,
    createdAt = createdAt,
    syncedAt = syncedAt,
    status = status
)
