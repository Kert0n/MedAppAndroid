package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тема окна — из семейства AppCompat, **во всех своих вариантах**. Окно наследует
 * `AppCompatActivity` ради языка приложения до Android 13 (PLAN C1 «Язык хранит система»), а она
 * требует темы своего семейства и отказывается рисовать с чужой: `setContentView` бросает
 * `IllegalStateException`, и приложение падает на запуске — не при выборе языка, а сразу.
 *
 * Держится это проверкой, а не вниманием, потому что тема объявлена не однажды: у `values-night`
 * своё объявление, и разъехаться им нечего стоит. Так и вышло — ночная тема осталась
 * `android:Theme.Material.NoActionBar`, когда дневная стала AppCompat, и приложение перестало
 * запускаться у всех, у кого включена тёмная тема. Прогоны этого не увидели: на эмуляторах ночной
 * режим выключен.
 *
 * Проверка читает дерево ресурсов, а не устройство: вариантов темы может стать больше —
 * `values-v33`, `values-ru-night`, — и каждый новый попадает сюда сам.
 */
class WindowThemeTest {

    private val resources: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory } ?: error("ресурсов нет: проверка прошла бы впустую")

    /** Семейство AppCompat: своего родителя тема называет прямо, и он же виден в объявлении. */
    private val appCompatParent = """parent="Theme\.AppCompat[.\w]*"""".toRegex()

    private val declarations: List<File> = resources.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values") }
        .map { it.resolve("themes.xml") }
        .filter { it.isFile }

    @Test
    fun everyDeclaredWindowThemeBelongsToAppCompat() {
        assertTrue("объявлений темы не нашлось: проверка прошла бы впустую", declarations.isNotEmpty())

        for (declaration in declarations) {
            val text = declaration.readText()
            if ("\"Theme.MedApp\"" !in text) continue
            assertTrue(
                "${declaration.parentFile?.name}/${declaration.name}: тема окна не из семейства AppCompat — " +
                    "с такой `AppCompatActivity` не рисует, и приложение падает на запуске",
                appCompatParent.containsMatchIn(text)
            )
        }
    }
}
