package com.kert0n.medapp.feature.template

import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.template.TemplateQuery
import java.time.Instant

/**
 * Кэш справочника: что сервер сказал последним, и поиск по этому без сети (PLAN H5).
 *
 * **Заготовка**: сценарием не используется, запись и чтение без сети не подключены (issue на кэш
 * справочника).
 */
interface TemplateRecords {

    suspend fun remember(templates: List<PackageTemplate>, at: Instant)

    suspend fun search(query: TemplateQuery, limit: Int): List<PackageTemplate>
}
