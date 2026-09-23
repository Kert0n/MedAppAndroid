package com.kert0n.medapp.storage.operation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.uuid.Uuid

/**
 * Зависимость операции от операции — отдельной таблицей, а не размазанная по payload: иначе
 * «что ещё не может уехать» пришлось бы искать разбором тел команд (PLAN F1, E2).
 */
@Entity(
    tableName = "sync_operation_dependencies",
    primaryKeys = ["operation_id", "depends_on_id"],
    foreignKeys = [
        ForeignKey(
            entity = SyncOperationStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = SyncOperationStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["depends_on_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("depends_on_id")]
)
class SyncOperationDependencyStorageEntity(
    @ColumnInfo(name = "operation_id") val operationId: Uuid,
    @ColumnInfo(name = "depends_on_id") val dependsOnId: Uuid
)
