package com.kert0n.medapp.storage.template

import androidx.room.withTransaction
import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.template.TemplateQuery
import com.kert0n.medapp.feature.template.TemplateRecords
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject

class PackageTemplateRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val templates: PackageTemplateDao,
    private val vocabulary: VocabularyDao
) : TemplateRecords {

    override suspend fun remember(templates: List<PackageTemplate>, at: Instant) =
        this.templates.upsert(templates.map { it.toStorageEntity(at) })

    /** Карточки и словарь — одной транзакцией: форма, записанная между ними, не останется без объекта. */
    override suspend fun search(query: TemplateQuery, limit: Int): List<PackageTemplate> = database.withTransaction {
        val words = vocabulary.snapshot()
        templates.search(query.text.lowercase(), limit).map { it.toDomain(words) }
    }
}
