package com.kert0n.medapp.storage.pack

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.pack.PackageRecord
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.value.DosageFormStorageEntity
import com.kert0n.medapp.storage.value.QuantityUnitStorageEntity
import com.kert0n.medapp.storage.value.storedForm
import com.kert0n.medapp.storage.value.storedUnit
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Вечная запись о коробке — зеркало `course_records`: то же тождество, что у живой строки
 * `packages`, и она остаётся, когда живой строки больше нет. За неё держатся приёмы
 * (`RESTRICT`), поэтому история читается с именем и единицей и после того, как коробка кончилась
 * или выброшена (PLAN D3, D6, F1). Снимок имени, единицы и формы переписывается вместе с
 * живой строкой; `added_at` — момент появления, у чужой пачки — первого наблюдения.
 */
@Entity(
    tableName = "package_records",
    foreignKeys = [
        ForeignKey(
            entity = QuantityUnitStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = DosageFormStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["form_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("unit_id"), Index("form_id")]
)
class PackageRecordStorageEntity(
    @PrimaryKey val id: Uuid,
    val name: String,
    @ColumnInfo(name = "unit_id") val unitId: Uuid,
    @ColumnInfo(name = "form_id") val formId: Uuid? = null,
    @ColumnInfo(name = "added_at") val addedAt: Instant
) {
    fun toDomain(vocabulary: Vocabulary): PackageRecord = PackageRecord(
        id = id,
        name = name,
        unit = vocabulary.storedUnit(unitId),
        form = formId?.let(vocabulary::storedForm),
        addedAt = addedAt
    )

    fun toRef(vocabulary: Vocabulary): PackageRef = toDomain(vocabulary).ref
}

fun PackageRecord.toStorageEntity(): PackageRecordStorageEntity = PackageRecordStorageEntity(
    id = id,
    name = name,
    unitId = unit.id,
    formId = form?.id,
    addedAt = addedAt
)
