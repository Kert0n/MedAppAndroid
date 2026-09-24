package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Адреса серверов приходят из сборки (`local.properties`, секреты CI), а не лежат в исходниках:
 * в репозитории их нет вовсе (AGENTS «Связь с сервером»). Разрешены только пространства имён
 * разметки Android — это не адреса, к которым ходят.
 */
class AddressesComeFromTheBuildTest {

    private val sources: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    @Test
    fun noAddressIsWrittenIntoTheApp() {
        val address = Regex("https?://(?!schemas\\.android\\.com/)[\\w.-]+")
        val found = sources.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "json") }
            .filter { address.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sources).invariantSeparatorsPath }
            .toSortedSet()
        assertEquals("адрес записан в исходники, а не пришёл из сборки", sortedSetOf<String>(), found)
    }
}
