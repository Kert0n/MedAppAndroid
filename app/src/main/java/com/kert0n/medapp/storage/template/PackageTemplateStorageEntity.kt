package com.kert0n.medapp.storage.template

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.value.DosageFormStorageEntity
import com.kert0n.medapp.storage.value.QuantityUnitStorageEntity
import com.kert0n.medapp.storage.value.storedForm
import com.kert0n.medapp.storage.value.storedUnit
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Кэш карточек справочника: найденное однажды ищется и без сети (PLAN F1, H5). `search_text` —
 * название, латинское название, вещество и производитель в нижнем регистре Kotlin: `lower()` в
 * SQLite знает только латиницу (H4). `cached_at` — когда сервер сказал это последним.
 *
 * **Заготовка.** Кэш под возможное решение «найденное доступно без сети» и нечёткий поиск: таблица,
 * DAO и порт есть, но сценарий их не зовёт, и ответ сервера сюда не пишется (PLAN H5, issue на
 * кэш справочника).
 */
@Entity(
    tableName = "drug_templates",
    foreignKeys = [
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
    indices = [Index("quantity_unit_id"), Index("form_id"), Index("cached_at")]
)
class PackageTemplateStorageEntity(
    @PrimaryKey val id: Uuid,
    val name: String,
    @ColumnInfo(name = "name_lat") val nameLat: String? = null,
    @ColumnInfo(name = "active_substance") val activeSubstance: String? = null,
    @ColumnInfo(name = "form_id") val formId: Uuid? = null,
    val category: String? = null,
    @ColumnInfo(name = "quantity_unit_id") val unitId: Uuid? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Instant
) {
    fun toDomain(vocabulary: Vocabulary): PackageTemplate = PackageTemplate(
        id = id,
        facts = PackageSharedFacts(
            name = name,
            form = formId?.let(vocabulary::storedForm),
            category = category,
            manufacturer = manufacturer,
            country = country,
            description = description
        ),
        unit = unitId?.let(vocabulary::storedUnit),
        nameLat = nameLat,
        activeSubstance = activeSubstance
    )
}

fun PackageTemplate.toStorageEntity(at: Instant): PackageTemplateStorageEntity = PackageTemplateStorageEntity(
    id = id,
    name = facts.name,
    nameLat = nameLat,
    activeSubstance = activeSubstance,
    formId = facts.form?.id,
    category = facts.category,
    unitId = unit?.id,
    manufacturer = facts.manufacturer,
    country = facts.country,
    description = facts.description,
    searchText = listOfNotNull(facts.name, nameLat, activeSubstance, facts.manufacturer).joinToString("\n").lowercase(),
    cachedAt = at
)
