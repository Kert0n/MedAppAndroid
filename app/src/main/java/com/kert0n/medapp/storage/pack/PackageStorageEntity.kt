package com.kert0n.medapp.storage.pack

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.storage.medkit.MedKitStorageEntity
import com.kert0n.medapp.storage.value.DosageFormStorageEntity
import com.kert0n.medapp.storage.value.QuantityUnitStorageEntity
import com.kert0n.medapp.storage.value.storedForm
import com.kert0n.medapp.storage.value.toStorageAmount
import com.kert0n.medapp.storage.value.toStorageSortKey
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Живая коробка — подтверждённая серверная часть упаковки: то, что снимок переписывает целиком.
 * Строка существует, пока коробка у нас есть; кончившаяся или выброшенная строки не оставляет
 * (PLAN D3). Личные сведения
 * лежат отдельной строкой, иначе срок годности и цена стирались бы при каждом обновлении
 * (PLAN F1).
 *
 * `version`, `claims_version` и `synced_at` — обвязка доставки: это `PackageSyncState` сетевого
 * слоя, а не свойство пачки.
 *
 * Две производные колонки существуют ради одного запроса списка (PLAN H4). `quantity_sort` —
 * то же число, дополненное нулями до предельной ширины величины, поэтому порядок по остатку
 * получается без `CAST(… AS REAL)` (F3). `name_search` — название в нижнем регистре: `lower()`
 * и `COLLATE NOCASE` в SQLite знают только латиницу, и по-русски поиск без учёта регистра иначе
 * не работает.
 *
 * Пачка не живёт без записи о себе, без аптечки и без единицы, в которой её считают: ключи
 * `RESTRICT` держат это в схеме, а не в коде (PLAN F2). Аптечку с пачками база удалить не даст —
 * сценарий сначала решает, куда им деться; словарь только растёт, и удалять из него нечего.
 */
@Entity(
    tableName = "packages",
    foreignKeys = [
        ForeignKey(
            entity = PackageRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MedKitStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["med_kit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = QuantityUnitStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["quantity_unit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = DosageFormStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["form_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("med_kit_id"), Index("name_search"), Index("quantity_unit_id"), Index("form_id")]
)
class PackageStorageEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "med_kit_id") val medKitId: Uuid,
    val name: String,
    @ColumnInfo(name = "name_search") val nameSearch: String,
    val quantity: String,
    @ColumnInfo(name = "quantity_sort") val quantitySort: String,
    @ColumnInfo(name = "quantity_unit_id") val quantityUnitId: Uuid,
    @ColumnInfo(name = "form_id") val formId: Uuid? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    val version: Long? = null,
    @ColumnInfo(name = "claims_version") val claimsVersion: Long? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Instant? = null,
    /** Неподтверждённое решение о коробке; снимок сервера его не переписывает (PLAN E1). */
    val status: PackageStatus = PackageStatus.ACTIVE
) {
    /** Аптечка пачки, прочитанная связью: её нет — строка пачки повреждена, ключ это держит (F2). */
    fun medKitRow(read: MedKitStorageEntity?): MedKitStorageEntity =
        requireNotNull(read) { "пачка лежит в аптечке, которой нет: $medKitId" }

    fun sharedFacts(vocabulary: Vocabulary): PackageSharedFacts = PackageSharedFacts(
        name = name,
        form = formId?.let(vocabulary::storedForm),
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )

    fun syncState(): PackageSyncState = PackageSyncState(
        packageId = id,
        version = version?.let(::ResourceVersion),
        claimsVersion = claimsVersion?.let(::ResourceVersion),
        syncedAt = syncedAt
    )
}

fun Package.toStorageEntity(sync: PackageSyncState = PackageSyncState(id)): PackageStorageEntity {
    require(sync.packageId == id) { "обвязка синхронизации принадлежит своей пачке" }
    return PackageStorageEntity(
        id = id,
        medKitId = medKit.id,
        name = facts.name,
        nameSearch = facts.name.lowercase(),
        quantity = quantity.toStorageAmount(),
        quantitySort = quantity.toStorageSortKey(),
        quantityUnitId = quantity.unit.id,
        formId = facts.form?.id,
        category = facts.category,
        manufacturer = facts.manufacturer,
        country = facts.country,
        description = facts.description,
        version = sync.version?.number,
        claimsVersion = sync.claimsVersion?.number,
        syncedAt = sync.syncedAt,
        status = status
    )
}
