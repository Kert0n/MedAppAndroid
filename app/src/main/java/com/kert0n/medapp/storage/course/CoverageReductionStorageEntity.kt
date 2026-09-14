package com.kert0n.medapp.storage.course

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.storage.pack.PackageRecordStorageEntity
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Событие сокращения обеспечения (PLAN D5). Держится за запись эпизода и запись о коробке —
 * то, что остаётся навсегда: событие переживает и конец лечения, и конец коробки, как история
 * приёмов (PLAN D3, D6). Читается по курсу и по времени.
 */
@Entity(
    tableName = "coverage_reductions",
    foreignKeys = [
        ForeignKey(
            entity = CourseRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["package_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    // Сверка читает недавние сокращения по моменту, а не перебором истории (PLAN D8).
    indices = [Index(value = ["course_id", "at"]), Index("package_id"), Index("at")]
)
class CoverageReductionStorageEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "course_id") val courseId: Uuid,
    @ColumnInfo(name = "package_id") val packageId: Uuid,
    @ColumnInfo(name = "covered_before") val coveredBefore: Int,
    @ColumnInfo(name = "covered_after") val coveredAfter: Int,
    val at: Instant
) {
    fun toDomain(): CoverageReduction =
        CoverageReduction(id, courseId, packageId, Doses(coveredBefore), Doses(coveredAfter), at)
}

fun CoverageReduction.toStorageEntity(): CoverageReductionStorageEntity =
    CoverageReductionStorageEntity(id, courseId, packageId, coveredBefore.count, coveredAfter.count, at)
