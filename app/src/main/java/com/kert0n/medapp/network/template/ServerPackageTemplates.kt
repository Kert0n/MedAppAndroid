package com.kert0n.medapp.network.template

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.domain.template.TemplateQuery
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import javax.inject.Inject

/**
 * Справочник этого сервера: коды ответа за границу сети не уходят (PLAN B5, H1). Форма и единица
 * карточки разрешаются словарём с одним дочитыванием; чего словарь не знает и после него, остаётся
 * пустым — карточка подсказывает, а не решает (H5). Карточка, которая пачкой не станет — сведения
 * длиннее, чем их принимает пачка, — в подсказки не попадает.
 */
class ServerPackageTemplates @Inject constructor(
    private val api: MedAppApi,
    private val vocabulary: VocabularyResolver
) : PackageTemplates {

    override suspend fun search(query: TemplateQuery): PackageTemplates.Search =
        when (val answer = api.searchTemplates(query.text, LIMIT)) {
            is ApiResult.Failure -> PackageTemplates.Search.Unavailable(answer.failure.asUnavailability())
            is ApiResult.Success -> {
                val cards = answer.value
                val words = when (val strict = vocabulary.resolve { words -> cards.forEach { it.resolveIn(words) }; words }) {
                    is VocabularyResolver.Resolution.Resolved -> strict.value
                    is VocabularyResolver.Resolution.Unresolved -> vocabulary.snapshot()
                }
                PackageTemplates.Search.Found(cards.mapNotNull { it.toDomain(words) })
            }
        }

    private companion object {
        /** Десять подсказок под полем ввода — больше человек не просматривает (H5). */
        const val LIMIT = 10
    }
}

/** Все ли словарные ссылки карточки знакомы снимку: промах дочитывает словарь. */
private fun PackageTemplateNetworkDTO.resolveIn(words: Vocabulary) {
    formId?.let(words::formOrMiss)
    unitId?.let(words::unitOrMiss)
}

internal fun PackageTemplateNetworkDTO.toDomain(words: Vocabulary): PackageTemplate? = attempt {
    PackageTemplate(
        id = id,
        facts = PackageSharedFacts(
            name = name.trim(),
            form = formId?.let(words::form),
            category = category.orNullIfBlank(),
            manufacturer = manufacturer.orNullIfBlank(),
            country = country.orNullIfBlank(),
            description = description.orNullIfBlank()
        ),
        unit = unitId?.let(words::unit),
        nameLat = nameLat.orNullIfBlank(),
        activeSubstance = activeSubstance.orNullIfBlank()
    )
}.getOrNull()

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
