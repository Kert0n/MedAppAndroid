package com.kert0n.medapp.storage.pack

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.pack.Claims
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Картина броней своей строкой: её двигают чужие действия, а не наши правки пачки, поэтому у
 * неё своё время жизни. Версия картины — предусловие запроса, и она лежит в колонках
 * `packages` вместе с остальной обвязкой доставки (PLAN F1).
 *
 * Отсутствие строки — это `null` у пачки: аптечка не опубликована либо доступ к ней утрачен.
 * Единицы здесь нет: бронь измеряется единицей своей пачки.
 */
@Entity(
    tableName = "claims",
    foreignKeys = [
        ForeignKey(
            entity = PackageStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["package_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
class ClaimsStorageEntity(
    @PrimaryKey @ColumnInfo(name = "package_id") val packageId: Uuid,
    val total: String,
    val mine: String? = null
) {
    fun toDomain(): Claims = Claims(total = BigDecimal(total), mine = mine?.let(::BigDecimal))
}

fun Claims.toStorageEntity(packageId: Uuid): ClaimsStorageEntity = ClaimsStorageEntity(
    packageId = packageId,
    total = total.toPlainString(),
    mine = mine?.toPlainString()
)
