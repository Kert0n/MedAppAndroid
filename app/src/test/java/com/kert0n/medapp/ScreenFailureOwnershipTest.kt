package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Работа экрана, которая не удалась по причине вне приложения, — полный диск, испорченная база, —
 * не закрывает приложение (ТЗ 4.3). Голый `launch` в `presentation/` отдаёт такой сбой
 * `viewModelScope`, а у того нет обработчика: на телефоне это падение процесса. Проверка читает
 * исходники, как `CancellationSafetyTest`, и называет каждое место файлом.
 */
class ScreenFailureOwnershipTest {

    private val presentation: File = listOf(
        File("src/main/java/com/kert0n/medapp/presentation"),
        File("app/src/main/java/com/kert0n/medapp/presentation")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    @Test
    fun noScreenWorkIsLaunchedWithoutAnOwnerOfItsFailure() {
        val offenders = presentation.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.contains(Regex("\\blaunch\\s*\\{")) }
                    .map { (index, _) -> "${file.relativeTo(presentation).invariantSeparatorsPath}:${index + 1}" }
            }
            .sorted()
            .toList()

        assertEquals("сбой работы экрана улетает мимо него и роняет приложение", emptyList<String>(), offenders)
    }
}
