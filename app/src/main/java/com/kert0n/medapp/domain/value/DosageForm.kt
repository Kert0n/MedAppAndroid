package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid

/**
 * Форма выпуска. Отдельный тип, а не общая «запись словаря» вместе с единицей: обёрток над
 * идентификаторами в домене нет (PLAN D1), и при одном общем типе ничто не помешало бы
 * подставить форму туда, где ждут единицу.
 *
 * Сущность: переименованная форма — та же форма, тождество — [id].
 */
class DosageForm(val id: Uuid, val name: String) {
    init {
        requireText(name, NAME_MAX_LENGTH, "DosageForm.name")
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is DosageForm && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DosageForm(id=$id, name=$name)"

    /** Имя словами-основами — тем же правилом, что и чужой текст. */
    val stems: List<String> get() = stems(name)

    companion object {
        const val NAME_MAX_LENGTH = 200

        /**
         * Как сравнивать текст о форме со словарём (PLAN H5). Словарь сервера и текст реестра
         * получены разными путями, и дословное совпадение — удача; сравниваются **основы слов**:
         * регистр, «ё», знаки препинания не значат ничего; сокращения раскрываются по списку
         * (те же случаи, что знал `scrapper/form_types.py`, приводивший справочник к словарю:
         * «в/в», «п/о», «р-р», «таб.»); слова-наполнители («для», «приготовления», «нанесения на»,
         * «лекарственные», «полости») не считаются — «р-р в/м» и «раствор для внутримышечного
         * введения» одно; окончание отбрасывается («таблетки»,
         * «таблетка», «покрытые», «покрытая» — одна основа); всё от первого числа — дозировка,
         * не форма. Совпадение — только целиком: либо форма узнана, либо нет. На встроенном
         * словаре ни две формы не сливаются — это держит проверка.
         */
        fun stems(text: String): List<String> {
            val words = expanded(text).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
            return words.takeWhile { !it.first().isDigit() }.filterNot { it in FILLERS }.map(::stem)
        }

        /**
         * Текст «капсулы/таблетки» называет две формы на выбор: косая черта между словами —
         * альтернатива. Сокращения с чертой («п/о», «в/в») раскрываются раньше, чем черта делит.
         */
        fun alternatives(text: String): List<List<String>> =
            expanded(text).split('/').map(::stems).filter { it.isNotEmpty() }.distinct()

        private fun expanded(text: String): String {
            var value = " " + text.lowercase().replace('ё', 'е') + " "
            for ((short, full) in SYNONYMS) value = value.replace(short, full)
            return value
        }

        /** Основа: без окончания у длинных слов, целиком у коротких. */
        private fun stem(word: String): String = when {
            word.length >= 6 -> word.dropLast(2)
            word.length >= 4 -> word.dropLast(1)
            else -> word
        }

        private val FILLERS = setOf(
            "для", "приготовления", "нанесения", "на", "лекарственные", "лекарственный", "лекарственная",
            "полости", "оболочку", "оболочки"
        )

        /** Раскрытия сокращений — порядок важен: длинные раньше коротких. */
        private val SYNONYMS: List<Pair<String, String>> = listOf(
            "п/пл/о" to " покрытые пленочной оболочкой ",
            "п/о" to " покрытые оболочкой ",
            "в/в" to " внутривенного введения ",
            "в/м" to " внутримышечного введения ",
            "п/к" to " подкожного введения ",
            "д/ин." to " для инъекций ",
            "д/инф." to " для инфузий ",
            "д/" to " для ",
            "р-р" to " раствор ",
            " табл." to " таблетки ",
            " таб." to " таблетки ",
            " капс." to " капсулы ",
            " сусп." to " суспензия ",
            " супп." to " суппозитории ",
            " пор." to " порошок ",
            " гран." to " гранулы ",
            " лиоф." to " лиофилизат ",
            " конц." to " концентрат ",
            " амп." to " ампулы "
        )
    }
}
