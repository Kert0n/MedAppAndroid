package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Стандартный `runCatching` глотает `CancellationException`, и отменённый вызов выглядит как сбой
 * (разбор #30). В `main` его нет: попытка пишется `attempt` из `domain/Attempt.kt`, который отмену
 * пропускает. Проверка читает исходники, как `LayerBoundariesTest`: договор держит она, а не
 * внимание, и нарушение называется файлом.
 */
class CancellationSafetyTest {

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    @Test
    fun noStandardRunCatchingInMainSources() {
        val offenders = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains(Regex("\\brunCatching\\b")) }
            .map { it.relativeTo(sources).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals("runCatching глотает отмену — пишите attempt (domain/Attempt.kt)", emptyList<String>(), offenders)
    }
}
