package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.template.PackageTemplate
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.domain.template.TemplateQuery
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred

/**
 * Справочник в памяти. Отвечает найденным по вхождению текста в название — так, как экрану
 * хватает, чтобы различить «нашлось» и «не нашлось»; нечёткость совпадения — дело сервера и
 * проверяется на нём. [asked] — с чем к нему приходили: сколько раз и с каким текстом — предмет
 * проверок экрана. Ответ на запрос можно **задержать** ([hold]): тогда он придёт, когда тест его
 * отпустит, — так проверяется, что старый ответ не перекрывает новый.
 */
class FakePackageTemplates(vararg cards: PackageTemplate) : PackageTemplates {

    private val cards = cards.toList()

    val asked = mutableListOf<TemplateQuery>()

    private val held = mutableMapOf<String, CompletableDeferred<PackageTemplates.Search>>()

    /** Чем отвечать всем, вместо поиска: например, `Unavailable(NO_CONNECTION)`. */
    var answer: PackageTemplates.Search? = null

    override suspend fun search(query: TemplateQuery): PackageTemplates.Search {
        asked += query
        held[query.text]?.let { return it.await() }
        return answer ?: found(query.text)
    }

    /** Ответ на [text] не приходит, пока тест не отпустит его [release]-ом. */
    fun hold(text: String) {
        held[text] = CompletableDeferred()
    }

    fun release(text: String) {
        requireNotNull(held[text]) { "ответ на «$text» не задерживали" }.complete(found(text))
    }

    private fun found(text: String) =
        PackageTemplates.Search.Found(cards.filter { it.facts.name.contains(text, ignoreCase = true) })
}

/** Карточка справочника: тест называет только то, что проверяет. */
fun template(
    id: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000071"),
    name: String = "Парацетамол",
    form: DosageForm? = TABLET_FORM,
    category: String? = null,
    manufacturer: String? = null,
    country: String? = null,
    description: String? = null,
    unit: QuantityUnit? = TABLETS,
    activeSubstance: String? = null
) = PackageTemplate(
    id = id,
    facts = PackageSharedFacts(name, form, category, manufacturer, country, description),
    unit = unit,
    activeSubstance = activeSubstance
)
