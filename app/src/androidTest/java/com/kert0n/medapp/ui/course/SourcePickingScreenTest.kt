package com.kert0n.medapp.ui.course

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.course.PackageAttachmentPresentationDTO
import com.kert0n.medapp.presentation.course.SourcePickingUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
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

    private fun candidate() = PackageAttachmentPresentationDTO(
        packageId = PACK,
        name = "Нурофен",
        medKitName = "Домашняя",
        availableToMe = QuantityPresentationDTO("20", TABLETS.toPresentationDTO())
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

    /** Подключать нечего — сказано, что с этим делать. */
    @Test
    fun nothingToAttachIsExplained() {
        show(SourcePickingUiState())

        compose.onNodeWithText("Подходящих пачек нет: заведите коробку на полке или укажите ей форму.")
            .assertIsDisplayed()
    }
}
