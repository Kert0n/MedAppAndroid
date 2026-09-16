package com.kert0n.medapp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Форма: поля прокручиваются, действия стоят подвалом (PLAN H3 «Дизайн», C1 «Действия формы
 * прижаты к низу»). Проверяется на заведомо длинной форме — на короткой всё видно и без правила.
 */
@RunWith(AndroidJUnit4::class)
class FormTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved = 0

    private fun showLongForm() {
        compose.setContent {
            MedAppTheme {
                Form(
                    actions = {
                        Text("Записать не вышло")
                        Button(onClick = { saved++ }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
                        TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
                    }
                ) {
                    for (line in 1..20) {
                        OutlinedTextField(
                            value = "",
                            onValueChange = {},
                            label = { Text("Поле $line") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    /**
     * «Сохранить» нажимается сразу, без прокрутки: до того как действия стали подвалом, кнопка у
     * длинной формы уезжала за край тем дальше, чем больше человек заполнил.
     */
    @Test
    fun theActionsAreReachableWithoutScrolling() {
        showLongForm()

        compose.onNodeWithText("Сохранить").assertIsDisplayed().performClick()
        compose.onNodeWithText("Отмена").assertIsDisplayed()

        assertEquals(1, saved)
    }

    /** Отказ записи стоит там же, у кнопки: его читают в тот момент, когда нажали. */
    @Test
    fun theRefusalStandsWithTheActions() {
        showLongForm()

        compose.onNodeWithText("Записать не вышло").assertIsDisplayed()
    }

    /** Прокручиваются при этом поля: до последнего долистывают, и он остаётся на экране. */
    @Test
    fun theFieldsStillScroll() {
        showLongForm()

        compose.onNodeWithText("Поле 20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
    }
}
