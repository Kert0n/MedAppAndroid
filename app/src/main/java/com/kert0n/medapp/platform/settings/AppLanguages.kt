package com.kert0n.medapp.platform.settings

import android.content.Context

/**
 * На каком языке приложение говорит с человеком (PLAN H3 №27, C1 «Язык хранит система»). Как и
 * разрешения, это состояние **системы**, а не наши данные: выбор хранит per-app locale, второй
 * истины в настройках приложения не заводится. [SYSTEM] — язык не выбран, приложение идёт за
 * устройством.
 *
 * Объявленные языки — ровно те, у которых есть [tag]; тот же список стоит в `localeConfig` и в
 * `localeFilters` сборки, равенство держит `LanguageDeclarationTest`.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    RUSSIAN("ru"),
    ENGLISH("en");

    companion object {

        /** Что объявлено системе и что она может предложить сама. */
        val declared: List<AppLanguage> get() = entries.filter { it.tag != null }

        fun ofTag(tag: String?): AppLanguage = declared.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * Порт языка. Читает и пишет представление; [speaking] нужен шторке: до Android 13 выбранный язык
 * применяется только к окну, и контекст, на котором строят уведомление, надо перевести здесь.
 */
interface AppLanguages {

    fun current(): AppLanguage

    /** Выбор применяется сразу: окно пересоздаётся, и человек видит его уже на новом языке. */
    fun choose(language: AppLanguage)

    /**
     * Контекст, говорящий на выбранном языке. На 13+ это любой контекст — система перевела всё.
     * До 13 — язык окна, если окно в этом процессе уже открывалось; холодный старт из фона
     * говорит языком системы (названная граница, PLAN C1).
     */
    fun speaking(context: Context): Context
}
