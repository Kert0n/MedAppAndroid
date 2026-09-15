package com.kert0n.medapp.app.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Движение в приложении одно, и задано оно **целиком**.
 *
 * Незаданный переход библиотека подменяет своим, а предиктивный жест назад — системным «сжатием
 * со всех сторон»: одно и то же действие выглядело по-разному в зависимости от того, пальцем его
 * сделали или кнопкой (замечание владельца 2026-09-15). Забытый переход при этом не падает и не
 * краснеет — он просто играет чужое, и заметить это можно только глазами. Поэтому сторож читает
 * сам граф, как `LayerBoundariesTest` читает дерево исходников.
 *
 * Красная проверка: убрать у `NavHost` любой из четырёх — тест называет пропавший.
 */
class MotionTest {

    private val shell: File = listOf(
        File("src/main/java/com/kert0n/medapp/app/navigation/MedAppShell.kt"),
        File("app/src/main/java/com/kert0n/medapp/app/navigation/MedAppShell.kt")
    ).firstOrNull { it.isFile } ?: error("оболочка не найдена: проверка прошла бы впустую")

    private val motion: File = listOf(
        File("src/main/java/com/kert0n/medapp/app/navigation/Motion.kt"),
        File("app/src/main/java/com/kert0n/medapp/app/navigation/Motion.kt")
    ).firstOrNull { it.isFile } ?: error("движение не найдено: проверка прошла бы впустую")

    @Test
    fun theGraphNamesEveryTransition() {
        val text = shell.readText()
        val missing = listOf("enterTransition", "exitTransition", "popEnterTransition", "popExitTransition")
            .filterNot { "$it = " in text }

        assertEquals("граф не называет переход — жест назад сыграет чужое", emptyList<String>(), missing)
    }

    /**
     * Экранам движение по отдельности не назначается. Экран не знает, откуда на него пришли:
     * назначенное ему «проявление» соседа сыграло бы и при уходе вглубь, и человек не увидел бы,
     * что углубился. Решает сам переход, у которого есть оба конца.
     */
    @Test
    fun noScreenIsGivenItsOwnMotion() {
        val own = shell.readText()
            .split("composable<")
            .drop(1)
            .filter { it.substringBefore(") {").contains("Transition = ") }
            .map { it.substringBefore(">") }

        assertEquals("экрану назначено своё движение", emptyList<String>(), own)
    }

    /** Соседей от глубины отличает сам переход — по обоим своим концам. */
    @Test
    fun sidewaysIsToldFromDepthByBothEndsOfTheMove() {
        val text = motion.readText()

        assertTrue("переход не смотрит на оба конца", "initialState.isPlace() && targetState.isPlace()" in text)
    }
}
