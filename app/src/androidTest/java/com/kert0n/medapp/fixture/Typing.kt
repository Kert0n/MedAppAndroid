package com.kert0n.medapp.fixture

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.closeSoftKeyboard

/**
 * Человек убирает клавиатуру и нажимает кнопку. Подвал формы на время ввода уходит (`ui/Form`) и
 * возвращается не в тот же кадр, а когда высота клавиатуры сошла на нет; нажми проверка сразу —
 * под нагрузкой кнопки ещё нет, и краснеет она не о коде. Поэтому нажимают, когда кнопка пришла.
 */
fun ComposeTestRule.pressAfterTyping(label: String, timeoutMillis: Long = 5_000) {
    closeSoftKeyboard()
    waitUntil(timeoutMillis) { onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText(label).performClick()
}
