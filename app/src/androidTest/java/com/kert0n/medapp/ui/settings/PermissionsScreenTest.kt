package com.kert0n.medapp.ui.settings

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.settings.PermissionState
import com.kert0n.medapp.presentation.settings.PermissionsPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Разрешения (PLAN H3 №27): что человек видит и куда его ведёт нажатие. Откуда берётся
 * состояние, проверяет `PermissionsViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var notificationsFixed = 0
    private var alarmsFixed = 0
    private var cameraFixed = 0

    private fun show(state: PermissionsPresentationDTO) {
        compose.setContent {
            MedAppTheme {
                PermissionsScreen(
                    state = state,
                    onFixNotifications = { notificationsFixed++ },
                    onFixAlarms = { alarmsFixed++ },
                    onFixCamera = { cameraFixed++ },
                    onBack = {}
                )
            }
        }
    }

    private fun row(name: String) = compose.onNode(hasClickAction() and hasAnyDescendant(hasText(name)), useUnmergedTree = true)

    /** Выключенное нажимается и ведёт туда, где чинится; разрешённое — нет: за нажатием ничего не стоит. */
    @Test
    fun aDeniedRowLeadsToItsFixAndAGrantedOneDoesNot() {
        show(PermissionsPresentationDTO(PermissionState.DENIED, PermissionState.GRANTED, PermissionState.GRANTED))

        compose.onNodeWithText("Выключено — исправить в настройках телефона").assertIsDisplayed()
        row("Уведомления").performClick()

        assertEquals(1, notificationsFixed)
        compose.onNode(hasAnyDescendant(hasText("Камера")) and hasClickAction(), useUnmergedTree = true).assertDoesNotExist()
    }

    /** Точные будильники ведут на свой экран, а не в настройки уведомлений. */
    @Test
    fun inexactAlarmsLeadToTheirOwnSettings() {
        show(PermissionsPresentationDTO(PermissionState.GRANTED, PermissionState.DENIED, PermissionState.GRANTED))

        compose.onNodeWithText("Выключены — напоминания могут опаздывать").assertIsDisplayed()
        row("Точные будильники").performClick()

        assertEquals(1, alarmsFixed)
        assertEquals(0, notificationsFixed)
    }

    /** Камеры нет — сказано словами, и нажимать нечего. */
    @Test
    fun anAbsentCameraIsExplainedAndNotClickable() {
        show(PermissionsPresentationDTO(PermissionState.GRANTED, PermissionState.NOT_NEEDED, PermissionState.ABSENT))

        compose.onNodeWithText("Нет на этом устройстве — коробки заводятся на полке").assertIsDisplayed()
        compose.onNodeWithText("Не требуется на этой версии Android").assertIsDisplayed()
        compose.onNode(hasAnyDescendant(hasText("Камера")) and hasClickAction(), useUnmergedTree = true).assertDoesNotExist()
    }

    /** Значок состояния говорит и экранному чтецу: у него есть описание, а не только цвет. */
    @Test
    fun stateIconsSpeakToTheScreenReader() {
        show(PermissionsPresentationDTO(PermissionState.GRANTED, PermissionState.GRANTED, PermissionState.GRANTED))

        assertEquals(3, compose.onAllNodesWithContentDescription("Разрешено", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
