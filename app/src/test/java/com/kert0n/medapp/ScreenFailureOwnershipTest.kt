package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Работа экрана, которая не удалась по причине вне приложения, — полный диск, испорченная база, —
 * не закрывает приложение (ТЗ 4.3). Голый `launch` и голый `stateIn` в `presentation/` отдают такой
 * сбой `viewModelScope`, а у того нет обработчика: на телефоне это падение процесса. Владелец
 * сбоя один — [OWNER]; проверка читает исходники, как `CancellationSafetyTest`, и называет каждое
 * место файлом.
 */
class ScreenFailureOwnershipTest {

    private val presentation: File = listOf(
        File("src/main/java/com/kert0n/medapp/presentation"),
        File("app/src/main/java/com/kert0n/medapp/presentation")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    private fun offenders(pattern: Regex): List<String> = presentation.walkTopDown()
        .filter { it.extension == "kt" && it.name != OWNER }
        .flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.contains(pattern) }
                .map { (index, _) -> "${file.relativeTo(presentation).invariantSeparatorsPath}:${index + 1}" }
        }
        .sorted()
        .toList()

    @Test
    fun noScreenWorkIsLaunchedWithoutAnOwnerOfItsFailure() {
        assertEquals("сбой работы экрана улетает мимо него и роняет приложение", emptyList<String>(), offenders(Regex("\\blaunch\\s*\\{")))
    }

    @Test
    fun noScreenReadingIsSharedWithoutAnOwnerOfItsFailure() {
        assertEquals("сбой чтения экрана улетает мимо него и роняет приложение", emptyList<String>(), offenders(Regex("\\.stateIn\\(")))
    }

    private companion object {
        /** Файл помощников, через которые экран работает и читает. */
        const val OWNER = "ScreenWork.kt"
    }
}
