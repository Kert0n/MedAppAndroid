package com.kert0n.medapp.storage.intake

import androidx.room.ColumnInfo
import java.time.Instant
import kotlin.uuid.Uuid

/** Когда из коробки брали последний раз — строка одного запроса на все коробки списка (PLAN D4). */
class PackageLastUseStorageRow(
    @ColumnInfo(name = "package_id") val packageId: Uuid,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Instant
)
