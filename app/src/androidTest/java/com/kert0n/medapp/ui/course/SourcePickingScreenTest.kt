package com.kert0n.medapp.ui.course

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.course.Attachability
import com.kert0n.medapp.presentation.course.PackageAttachmentPresentationDTO
import com.kert0n.medapp.presentation.course.SourcePickingUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Выбор источника (PLAN H3 №17): что человек видит и что нажимается. */
@RunWith(AndroidJUnit4::class)
class SourcePickingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var attached: Uuid? = null

    private fun show(state: SourcePickingUiState) {
        compose.setContent {
            MedAppTheme {
                SourcePickingScreen(state = state, onAttach = { attached = it }, onBack = {})
            }
        }
    }

    private fun candidate(
        attachability: Attachability = Attachability.Attachable
    ) = PackageAttachmentPresentationDTO(
        packageId = PACK,
        name = "Нурофен",
        medKitName = "Домашняя",
        availableToMe = QuantityPresentationDTO("20", TABLETS.toPresentationDTO()),
        attachability = attachability
    )

    /** Строка говорит, чем коробка названа, где лежит и сколько в ней моего. */
    @Test
    fun aCandidateTellsItsShelfAndWhatIsFree() {
        show(SourcePickingUiState(packages = listOf(candidate())))

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("Домашняя · свободно 20 таблетка").assertIsDisplayed()
    }

    /** Нажатие подключает — и человек возвращается туда, где виден весь стек. */
    @Test
    fun tappingABoxAttachesIt() {
        show(SourcePickingUiState(packages = listOf(candidate())))

        compose.onNodeWithText("Нурофен").performClick()

        assertEquals(PACK, attached)
    }

    /** Неподходящая коробка остаётся на виду и говорит, почему её нельзя взять. */
    @Test
    fun anUnsuitableBoxStaysVisibleWithItsReason() {
        show(SourcePickingUiState(packages = listOf(candidate(Attachability.HeldByCourse("Ибупрофен")))))

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("Занята лечением «Ибупрофен».").assertIsDisplayed()
    }

    /** Нажатие по неподходящей ничего не подключает: причина названа, и решение за человеком. */
    @Test
    fun tappingAnUnsuitableBoxDoesNothing() {
        show(SourcePickingUiState(packages = listOf(candidate(Attachability.NeedsForm))))

        compose.onNodeWithText("Нурофен").performClick()

        assertNull(attached)
        compose.onNodeWithText("Укажите форму выпуска у этой пачки, чтобы подключить её к курсу.")
            .assertIsDisplayed()
    }

    /** Подключать нечего — сказано, что с этим делать. */
    @Test
    fun nothingToAttachIsExplained() {
        show(SourcePickingUiState())

        compose.onNodeWithText("Подходящих пачек нет: заведите коробку на полке или укажите ей форму.")
            .assertIsDisplayed()
    }
}
