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

    /** Имя, приведённое к виду для сравнения — тем же правилом, что и чужой текст. */
    val normalizedName: String get() = normalized(name)

    companion object {
        const val NAME_MAX_LENGTH = 200

        /**
         * Как сравнивать текст о форме со словарём (PLAN H5): регистр, знаки препинания и лишние
         * пробелы не значат ничего, а сокращения раскрываются по **контролируемому** списку —
         * ровно тому, что здесь; догадок о прочих нет. Совпадения по названию препарата для
         * формы недостаточно.
         */
        fun normalized(text: String): String = strip(expanded(text))

        /**
         * Текст «капсулы/таблетки» называет две формы на выбор: косая черта между словами —
         * альтернатива, а не знак препинания. Сокращения раскрываются раньше, чем черта делит
         * текст, — иначе «п/о» стало бы двумя формами.
         */
        fun alternatives(text: String): List<String> =
            expanded(text).split('/').map(::strip).filter { it.isNotEmpty() }.distinct()

        private fun expanded(text: String): String {
            var value = text.lowercase().replace('ё', 'е')
            for ((short, full) in SYNONYMS) value = value.replace(short, full)
            return value
        }

        private fun strip(text: String): String = text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /**
         * Сокращения одного слова. Сокращений с косой чертой («п/о», «д/в/в») здесь нет: их
         * раскрытие меняет порядок слов и точного имени всё равно не даст, а черта между словами
         * значит выбор — такой текст честно уходит в «несколько».
         */
        private val SYNONYMS: List<Pair<String, String>> = listOf(
            "таб." to "таблетки ",
            "капс." to "капсулы ",
            "р-р" to "раствор",
            "сусп." to "суспензия ",
            "супп." to "суппозитории ",
            "пор." to "порошок "
        )
    }
}
