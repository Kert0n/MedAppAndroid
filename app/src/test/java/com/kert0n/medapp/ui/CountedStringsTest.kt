package com.kert0n.medapp.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Число в строке — это не подстановка, а грамматика. «Внутри 2 упаковок» по-русски неправильно,
 * и заметить это в строковом файле глазами нельзя: строка выглядит законченной.
 *
 * Правило: строка, в которую подставляется **число**, — `<plurals>`, и берётся она
 * `pluralStringResource`. Форм у русского счёта четыре, и какую взять, решает система по самому
 * числу, а не автор строки по тому, какое число он представил себе первым.
 *
 * Стережётся сам файл, а не экраны: экран с неверной формой ловится по одному тексту за раз, а
 * забытая строка не ловится вовсе — пока кто-нибудь не увидит её на устройстве с другим числом.
 */
class CountedStringsTest {

    private val strings = File("src/main/res/values/strings.xml").readText()

    /** `%d` в любом виде: и одиночный, и позиционный — счёт от этого не меняется. */
    private val counted = Regex("""%(\d+\$)?d""")

    @Test
    fun everyStringThatCountsIsAPluralOne() {
        val offenders = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings)
            .filter { counted.containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .toList()

        assertEquals("число подставлено в строку без склонения — нужен <plurals>", emptyList<String>(), offenders)
    }

    /** Обратное тоже правило: `<plurals>` без числа склонять нечем — значит, это простая строка. */
    @Test
    fun everyPluralActuallyCounts() {
        val mute = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings)
            .filterNot { counted.containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .toList()

        assertEquals("склонение без числа: это простая строка", emptyList<String>(), mute)
    }
}
