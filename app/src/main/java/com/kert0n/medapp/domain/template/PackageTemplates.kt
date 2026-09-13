package com.kert0n.medapp.domain.template

import com.kert0n.medapp.domain.Unavailability

/**
 * Поиск по справочнику сервера. Справочник живёт только там, поэтому действие выполняет сеть, а
 * домен называет его и его исходы (PLAN H1, H5).
 */
interface PackageTemplates {

    suspend fun search(query: TemplateQuery): Search

    /** Что ответил сервер — ровно те случаи, которые сценарий разбирает по-разному. */
    sealed interface Search {

        /** Сервер ответил; пустой список — «не найдено», а не ошибка. */
        data class Found(val templates: List<PackageTemplate>) : Search

        data class Unavailable(val reason: Unavailability) : Search
    }
}
