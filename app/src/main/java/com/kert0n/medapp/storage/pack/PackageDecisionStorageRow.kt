package com.kert0n.medapp.storage.pack

import androidx.room.ColumnInfo
import com.kert0n.medapp.domain.pack.PackageStatus
import kotlin.uuid.Uuid

/**
 * Решение о коробке, уже лежащее в базе: пометка и команда, которая её поставила (PLAN E1).
 * Читаются вместе, потому что порознь они невыразимы — пометка без своей команды не снимается
 * никогда, а команда без пометки ничего не держит; пусто — коробки нет.
 */
class PackageDecisionStorageRow(
    val status: PackageStatus,
    @ColumnInfo(name = "decided_by") val decidedBy: Uuid?
)
