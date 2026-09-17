package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что окно обещает системе — держится проверкой, а не вниманием: настоящей клавиатуры ни одной
 * проверке не показать, и перекос от неё ловится только у объявления, из которого он растёт.
 *
 * **Клавиатуру окно отдаёт форме, а не двигает собой.** Не назвав режим, окно отдаёт выбор
 * системе, и та **панорамирует** его: форма поднимает поля своим `imePadding`, а окно сверх того
 * уезжает вверх целиком. Верхняя панель прячется под строку состояния, а снизу остаётся пустой
 * прямоугольник — тем больше, чем ниже на экране было поле, потому что ровно настолько его и
 * поднимали (находка владельца 2026-09-17, видно на длинных формах: упаковка, лечение).
 * `adjustResize` отдаёт высоту клавиатуры insets'ами, и подъём остаётся один — тот, что рисует
 * форма.
 *
 * Выбирает за окно система, и выбор зависит от версии: на Android 10 панорамирование
 * воспроизводится, на Android 15 — нет. Поэтому проверка стоит у объявления: она стережёт наше
 * решение, а не сегодняшнее поведение той версии, что оказалась под рукой.
 */
class WindowDeclarationTest {

    private val manifest: File = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .firstOrNull { it.isFile } ?: error("манифеста нет: проверка прошла бы впустую")

    @Test
    fun theWindowGivesTheKeyboardToTheFormInsteadOfMovingItself() {
        val text = manifest.readText()
        val window = text.substringAfter("<activity").substringBefore(">")

        assertTrue(
            "окно приложения не назвало режим клавиатуры: система выберет сама и будет двигать окно\n$window",
            """android:windowSoftInputMode\s*=\s*"adjustResize"""".toRegex().containsMatchIn(window)
        )
    }
}
