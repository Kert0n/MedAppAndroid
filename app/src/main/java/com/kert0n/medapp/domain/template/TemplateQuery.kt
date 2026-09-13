package com.kert0n.medapp.domain.template

/**
 * Что человек ищет в справочнике: обрезанный текст от одного до [MAX_LENGTH] символов (PLAN B4).
 * Пустой ввод запросом не становится — искать нечего, и это решает экран.
 */
@JvmInline
value class TemplateQuery(val text: String) {
    init {
        require(text.isNotBlank() && text == text.trim()) { "запрос справочника — обрезанный непустой текст" }
        require(text.length <= MAX_LENGTH) { "запрос справочника длиннее $MAX_LENGTH символов" }
    }

    companion object {
        const val MAX_LENGTH = 200
    }
}
