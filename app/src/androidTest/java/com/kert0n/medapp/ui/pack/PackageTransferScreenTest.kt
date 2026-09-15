package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageTransferRefusal
import com.kert0n.medapp.presentation.pack.PackageTransferUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Экран 11 (PLAN H3): куда переложить коробку, и что сказано, когда некуда. */
@RunWith(AndroidJUnit4::class)
class PackageTransferScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: Uuid? = null
    private var transferred = 0

    private fun place(id: Uuid, name: String, location: String? = null, shared: Boolean = false): MedKitPresentationDTO =
        medKit(
            id = id, name = name, location = location,
            publication = if (shared) MedKit.Publication.PUBLISHED else MedKit.Publication.LOCAL,
            participantCount = if (shared) 2 else 1
        ).projection(MedKitContents.EMPTY).toPresentationDTO()

    private fun show(state: PackageTransferUiState) {
        compose.setContent {
            MedAppTheme {
                PackageTransferScreen(
                    state = state,
                    onChoose = { chosen = it },
                    onTransfer = { transferred++ },
                    onBack = {}
                )
            }
        }
    }

    private fun transfer(
        places: List<MedKitPresentationDTO> = listOf(place(HOME_KIT, "Домашняя", location = "В ванной"), place(SHARED_KIT, "Дача", shared = true)),
        chosen: Uuid? = null,
        refusal: PackageTransferRefusal? = null
    ) = PackageTransferUiState(places = places, chosen = chosen, refusal = refusal, isLoaded = true)

    /** Пока полки не прочитаны — загрузка, а не «некуда». */
    @Test
    fun whileTheShelvesAreUnreadTheScreenWaits() {
        show(PackageTransferUiState())
        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Переносить некуда", substring = true).assertDoesNotExist()
    }

    /** Места названы; у общей полки подпись, у местной — её место хранения. */
    @Test
    fun placesAreNamedAndTheSharedOneIsMarked() {
        show(transfer())

        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("В ванной").assertIsDisplayed()
        compose.onNodeWithText("Дача").assertIsDisplayed()
        compose.onNodeWithText("Общая аптечка").assertIsDisplayed()
    }

    /** Кнопка погашена, пока место не выбрано; выбор — нажатие на строку. */
    @Test
    fun theButtonWaitsForAChoiceAndTheRowIsTheChoice() {
        show(transfer())

        compose.onNodeWithText("Перенести").assertIsNotEnabled()
        compose.onNodeWithText("Дача").performClick()
        assertEquals(SHARED_KIT, chosen)
    }

    @Test
    fun withAChoiceTheButtonTransfers() {
        show(transfer(chosen = SHARED_KIT))

        compose.onNodeWithText("Перенести").assertIsEnabled().performClick()

        assertEquals(1, transferred)
    }

    /** Переносить некуда — рассказ, а не пустой список. */
    @Test
    fun withNowhereToMoveTheScreenSaysSo() {
        show(transfer(places = emptyList()))

        compose.onNodeWithText("Переносить некуда: другой аптечки пока нет. Заведите вторую — и коробку будет куда положить.").assertIsDisplayed()
        compose.onNodeWithText("Перенести").assertDoesNotExist()
    }

    /** Отказ виден причиной. */
    @Test
    fun aRefusalIsInWords() {
        show(transfer(chosen = SHARED_KIT, refusal = PackageTransferRefusal.TARGET_BUSY))

        compose.onNodeWithText("Выбранная аптечка ждёт ответа сервера — класть в неё пока рано.").assertIsDisplayed()
    }

    @Test
    fun aGoneBoxIsSaidOutLoud() {
        show(PackageTransferUiState(isGone = true, isLoaded = true))
        compose.onNodeWithText("Этой упаковки больше нет.").assertIsDisplayed()
    }
}
