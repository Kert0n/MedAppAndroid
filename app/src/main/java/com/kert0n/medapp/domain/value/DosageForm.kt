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
         * пробелы не значат ничего. Словарь и чужой текст получены разными путями, и совпадение
         * имён — удача, а не правило; поэтому рядом с точным сравнением есть [stem] — по нему
         * подбираются **кандидаты** на выбор человеку.
         */
        fun normalized(text: String): String = strip(expanded(text))

        /**
         * Основа первого слова — первые [STEM_LENGTH] букв: «таблетки», «таблетка», «табл.» и
         * «ТАБЛЕТКИ ПОКРЫТЫЕ…» дают одну основу «табле», и окончания, число и сокращения ей не
         * мешают. Короче [MIN_STEM] букв основа не бывает: по одной-двум буквам подбирать нечего.
         */
        fun stem(normalizedText: String): String? =
            normalizedText.substringBefore(' ').take(STEM_LENGTH).takeIf { it.length >= MIN_STEM }

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

        private const val STEM_LENGTH = 5
        private const val MIN_STEM = 3

        /**
         * Сокращения, которые основой не ловятся: «р-р» после чистки знаков — две буквы. Прочие
         * («таб.», «капс.», «сусп.») основа берёт сама. Сокращений с косой чертой («п/о») нет: черта
         * между словами значит выбор.
         */
        private val SYNONYMS: List<Pair<String, String>> = listOf(
            "р-р" to "раствор"
        )
    }
}
