package com.kert0n.medapp.feature.packages

import kotlin.uuid.Uuid

/**
 * Что показать в списке упаковок. Три независимых поля, а не история нажатий: конвейер один —
 * аптечки, поиск, фильтр, просроченные вперёд, сортировка, — и порядок нажатий результат не
 * меняет (PLAN H4).
 *
 * Фильтр ровно один: два одновременных сузили бы список до пустого чаще, чем помогли.
 */
data class PackageQuery(
    val medKitId: Uuid? = null,
    val text: String = "",
    val filter: Filter? = null,
    val sort: Sort = Sort.NAME
) {
    /**
     * Текст поиска в том виде, в каком его сравнивает база. Приведение к нижнему регистру
     * делает Kotlin: `lower()` и `COLLATE NOCASE` в SQLite знают только латиницу, и
     * «парацетамол» не нашёл бы «Парацетамол». «Ё» сводится к «е» — названия пишут то так, то
     * так, — а `\`, `%` и `_` экранируются: набранное ищется буквально, а не шаблоном `LIKE`.
     */
    val searchText: String = text.trim().lowercase().replace('ё', 'е')
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    sealed interface Filter {
        data object Expired : Filter
        data class ExpiringWithin(val days: Long) : Filter
        data object OnCourse : Filter

        /**
         * Свободное есть. Единственный фильтр, который не выражается запросом: «свободно»
         * считается вычитанием чужих броней и своих выделений из оценки количества, а оценка
         * зависит от незакрытых команд очереди. Его накладывает репозиторий поверх выборки
         * (PLAN D4, E1).
         */
        data object HasFree : Filter
        data class OfCategory(val category: String) : Filter
        data class OfForm(val formId: Uuid) : Filter
    }

    /** По количеству — от меньшего: список ведёт к тому, что кончается, а не к запасам. */
    enum class Sort { NAME, EXPIRY, ADDED_AT, QUANTITY }
}
