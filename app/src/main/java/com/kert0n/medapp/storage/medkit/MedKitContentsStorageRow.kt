package com.kert0n.medapp.storage.medkit

import androidx.room.ColumnInfo
import com.kert0n.medapp.domain.medkit.MedKitContents
import kotlin.uuid.Uuid

/** Сколько живых коробок лежит на полке и сколько из них просрочено — строка одного запроса на все полки. */
class MedKitContentsStorageRow(
    @ColumnInfo(name = "med_kit_id") val medKitId: Uuid,
    val packages: Int,
    val expired: Int
) {
    fun toDomain(): MedKitContents = MedKitContents(packages, expired)
}
