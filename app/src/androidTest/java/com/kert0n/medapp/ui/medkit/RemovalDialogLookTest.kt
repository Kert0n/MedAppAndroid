package com.kert0n.medapp.ui.medkit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Разговор об уборке полки на **крупном шрифте** (PLAN H3 №4). Три действия и «Отмена» должны
 * умещаться и нажиматься: слот кнопок `AlertDialog` — один ряд, и колонка в нём обрезала нижнее
 * действие (находка владельца на телефоне, 2026-09-17).
 */
@RunWith(AndroidJUnit4::class)
class RemovalDialogLookTest {

    @get:Rule
    val compose = createComposeRule()

    private var thrownAway = 0
    private var picked = 0
    private var dismissed = 0
    private var left = 0

    @Test
    fun everyFateAndCancelAreReachable() {
        compose.setContent {
            // Крупный шрифт задаётся здесь, а не устройством: беда у владельца случилась на
            // телефоне с поднятым масштабом, и ждать нужного эмулятора проверке незачем.
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
            MedAppTheme {
                MedKitContentsScreen(
                    state = MedKitContentsUiState(
                        medKit = medKit(id = HOME_KIT, name = "кадабра")
                            .projection(MedKitContents(packages = 4, expired = 0))
                            .toPresentationDTO(),
                        others = listOf(
                            medKit(id = kotlin.uuid.Uuid.random(), name = "Дача")
                                .projection(MedKitContents(packages = 0, expired = 0))
                                .toPresentationDTO()
                        ),
                        isLoaded = true,
                        removing = RemovalStep.ASKING
                    ),
                    onSearch = {}, onNarrow = {}, onOrder = {}, onReset = {},
                    onOpen = {}, onAdd = {}, onEdit = {}, onShare = {},
                    onAskToRemove = {}, onPickTarget = { picked++ }, onDismissRemoval = { dismissed++ },
                    onRemove = { thrownAway++ },
                    onLeave = { left++ }, onBack = {}
                )
            }
            }
        }

        // Сторожит **геометрия**, а не «видно» и не «нажимается»: обрезанную наполовину строку
        // `assertIsDisplayed` считает показанной, а `performClick` по ней попадает. Наружу
        // вылезала именно она.
        val dialog = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
        for (action in listOf("Убрать вместе с лекарствами", "Перенести и убрать", "Отмена")) {
            val bounds = compose.onNodeWithText(action).getUnclippedBoundsInRoot()
            assertTrue(
                "«$action» вылезла за диалог: ${bounds.bottom} против ${dialog.bottom}",
                bounds.bottom <= dialog.bottom
            )
        }

        compose.onNodeWithText("Убрать вместе с лекарствами").performClick()
        compose.onNodeWithText("Перенести и убрать").performClick()
        compose.onNodeWithText("Отмена").performClick()

        assertEquals(1, thrownAway)
        assertEquals(1, picked)
        assertEquals(1, dismissed)
    }

    /**
     * У общей полки судьбы три, и выход назван своим последствием; лечения, которые потеряют
     * источники, названы **до** решения, вместе со словами о том, что сами лечения останутся
     * (PLAN C1 «Курсы при выходе»).
     */
    @Test
    fun aSharedShelfOffersLeavingAndNamesWhatItCosts() {
        compose.setContent {
            MedAppTheme {
                MedKitContentsScreen(
                    state = MedKitContentsUiState(
                        medKit = medKit(
                            id = HOME_KIT, name = "Семейная",
                            publication = MedKit.Publication.PUBLISHED, participantCount = 3
                        ).projection(MedKitContents(packages = 4, expired = 0)).toPresentationDTO(),
                        isLoaded = true,
                        removing = RemovalStep.ASKING,
                        affectedCourses = listOf("Спина", "Колено")
                    ),
                    onSearch = {}, onNarrow = {}, onOrder = {}, onReset = {},
                    onOpen = {}, onAdd = {}, onEdit = {}, onShare = {},
                    onAskToRemove = {}, onPickTarget = {}, onDismissRemoval = {},
                    onRemove = {}, onLeave = { left++ }, onBack = {}
                )
            }
        }

        compose.onNodeWithText("Источники потеряют: Спина, Колено. Сами лечения останутся.").assertIsDisplayed()
        compose.onNodeWithText("Аптечка останется у других, а вам её лекарства будут недоступны").assertIsDisplayed()
        compose.onNodeWithText("Выйти и оставить остальным").performClick()

        assertEquals(1, left)
    }
}
