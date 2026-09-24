package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порты хранения нарезаны по потребителю (AGENTS «Инварианты»): запись сценария — `*Records`,
 * чтения экрана — `*Readings`. Член чтений, которого экран не зовёт, живёт не в своём порте: либо
 * он нужен только сценарию и принадлежит `*Records`, либо не нужен никому.
 */
class ReadingsOwnershipTest {

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    /** Чтения, которые для экрана собирает сценарий чтения, а не сам экран, — поимённо. */
    private val composedForTheScreen: Map<String, String> = mapOf(
        "ReportReadings.observeDayPlan" to "feature/plan/DayPlanning.kt"
    )

    @Test
    fun everyReadingIsReadByAScreen() {
        val screens = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.relativeTo(sources).invariantSeparatorsPath.let { p -> p.startsWith("presentation/") || p.startsWith("ui/") } }
            .map { it.readText() }
            .toList()
        val read = screens.flatMap { callsOnPorts(it) }.toSet()
        val strays = sources.resolve("feature").walkTopDown()
            .filter { it.name.endsWith("Readings.kt") }
            .flatMap { file ->
                val port = file.nameWithoutExtension
                Regex("(?m)^\\s+(?:suspend )?fun (\\w+)\\(").findAll(file.readText()).map { "$port.${it.groupValues[1]}" }
            }
            .filter { member ->
                val composer = composedForTheScreen[member]?.let { callsOnPorts(File(sources, it).readText()) }.orEmpty()
                member !in read && member !in composer
            }
            .toSortedSet()
        assertEquals("чтения, которых экран не зовёт", sortedSetOf<String>(), strays)
    }

    /**
     * Вызовы на портах чтения в тексте: `порт.метод` для каждого поля или параметра, объявленного
     * типом `…Readings`, — чтобы одноимённый метод другого порта не засчитывался за этот.
     */
    private fun callsOnPorts(text: String): Set<String> {
        val holders = Regex("\\b(\\w+)\\s*:\\s*(\\w+Readings)\\b").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        return holders.flatMap { (name, port) ->
            Regex("\\b${name}\\.(\\w+)\\(").findAll(text).map { "$port.${it.groupValues[1]}" }.toList()
        }.toSet()
    }
}
