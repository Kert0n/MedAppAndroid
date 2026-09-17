package com.kert0n.medapp.platform.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import javax.inject.Inject

/**
 * Язык — у AppCompat: на Android 13+ он делегирует `LocaleManager`, ниже хранит выбор в своём
 * файле (`autoStoreLocales`) и применяет его к `AppCompatActivity` при создании (PLAN C1 «Язык
 * хранит система»). Пустой список локалей — «как в системе».
 */
class AppCompatAppLanguages @Inject constructor() : AppLanguages {

    override fun current(): AppLanguage =
        AppLanguage.ofTag(AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language)

    override fun choose(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            language.tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        )
    }

    override fun speaking(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val chosen = AppCompatDelegate.getApplicationLocales()
        if (chosen.isEmpty) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(chosen.toLanguageTags()))
        return context.createConfigurationContext(configuration)
    }
}
