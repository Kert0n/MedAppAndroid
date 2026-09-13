package com.kert0n.medapp.storage.template

import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.template.TemplateQuery
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject

class PackageTemplateRoomRepository @Inject constructor(
    private val templates: PackageTemplateDao,
    private val vocabulary: VocabularyDao
) : PackageTemplateStorageRepository {

    override suspend fun remember(templates: List<PackageTemplate>, at: Instant) =
        this.templates.upsert(templates.map { it.toStorageEntity(at) })

    override suspend fun search(query: TemplateQuery, limit: Int): List<PackageTemplate> {
        val words = vocabulary.snapshot()
        return templates.search(query.text.lowercase(), limit).map { it.toDomain(words) }
    }
}
