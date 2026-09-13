package com.kert0n.medapp.feature.template

import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.domain.template.TemplateQuery
import javax.inject.Inject

/**
 * Человек ищет препарат в справочнике, чтобы заполнить новую пачку (ТЗ 4.1.1.12; PLAN H5). Ищет
 * сервер — справочник живёт только там, и без связи подсказок нет: экран называет причину, а
 * пачку можно завести руками. Дебаунс и отмена прежнего запроса — дело экрана: сценарий отвечает на
 * тот запрос, с которым его позвали. Кэш найденного без сети — заготовка в `storage/template/`,
 * сценарием не используется.
 */
class TemplateSearching @Inject constructor(
    private val templates: PackageTemplates
) {

    suspend fun search(query: TemplateQuery): PackageTemplates.Search = templates.search(query)
}
