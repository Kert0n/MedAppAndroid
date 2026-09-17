package com.kert0n.medapp

import com.kert0n.medapp.platform.settings.AppLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приложение объявляет свой язык, и объявление держится проверкой, а не вниманием (PLAN C1
 * «Язык хранит система», U12.1): без `localeConfig` система не знает, что предложить, а список
 * языков живёт в трёх местах — у порта, в объявлении и в сборке — и разъехался бы первым.
 * Без `autoStoreLocales` выбор до Android 13 не пережил бы перезапуск.
 */
class LanguageDeclarationTest {

    private val main: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory } ?: error("исходников нет: проверка прошла бы впустую")

    private val manifest = main.resolve("AndroidManifest.xml").readText()

    /** Без `localeConfig` системе нечего предложить в настройках языка приложения. */
    @Test
    fun theManifestDeclaresTheLanguages() {
        assertTrue("приложение не объявило языков: системе нечего предложить", "android:localeConfig=\"@xml/locales_config\"" in manifest)
    }

    /** Без `autoStoreLocales` выбор языка до Android 13 не пережил бы перезапуск. */
    @Test
    fun theChoiceIsStoredBeforeAndroid13() {
        assertTrue("выбор языка до Android 13 не сохраняется", "autoStoreLocales" in manifest)
    }

    /** Объявленные языки — ровно те, что знает порт: лишний нечем показать, недостающий нечем выбрать. */
    @Test
    fun declaredLocalesAreExactlyThePortsLanguages() {
        val declared = """android:name="([a-zA-Z-]+)"""".toRegex()
            .findAll(main.resolve("res/xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.toList()

        assertEquals(AppLanguage.declared.map { it.tag }, declared)
    }

    /** Сборка кладёт в APK переводы ровно объявленных языков. */
    @Test
    fun theBuildKeepsExactlyTheDeclaredLocales() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        val filters = """localeFilters\s*\+=\s*listOf\(([^)]*)\)""".toRegex().find(build)
            ?: error("сборка не ограничила языки: в APK поедут переводы библиотек на всё")

        assertEquals(
            AppLanguage.declared.map { it.tag },
            filters.groupValues[1].split(",").map { it.trim().trim('"') }
        )
    }

    /**
     * У каждого объявленного языка, кроме умолчания, есть свой полный набор строк: пропущенная
     * строка ловится lint'ом, но лишняя или переименованная — только сверкой имён.
     */
    @Test
    fun everyDeclaredLanguageHasEveryString() {
        val names = { file: File -> """<(?:string|plurals|string-array) name="([^"]+)"""".toRegex().findAll(file.readText()).map { it.groupValues[1] }.toSet() }
        val defaults = main.resolve("res/values/strings.xml")
        val untranslatable = """<string name="([^"]+)" translatable="false"""".toRegex().findAll(defaults.readText()).map { it.groupValues[1] }.toSet()
        for (language in AppLanguage.declared) {
            val translation = main.resolve("res/values-${language.tag}/strings.xml")
            if (!translation.isFile) continue // язык умолчания лежит в values/
            assertEquals("values-${language.tag} расходится с values", names(defaults) - untranslatable, names(translation))
        }
    }
}
