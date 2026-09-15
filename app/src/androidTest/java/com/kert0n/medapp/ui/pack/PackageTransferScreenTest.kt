package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageTransferUiState
import com.kert0n.medapp.presentation.pack.TransferRefusal
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Перенос упаковки (PLAN H3 №11): куда можно положить и что бывает, когда некуда. */
@RunWith(AndroidJUnit4::class)
class PackageTransferScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: Uuid? = null
    private var transferred = 0

    private val home = medKit(id = HOME_KIT, name = "Домашняя", location = "Ванная")
        .projection(MedKitContents.EMPTY).toPresentationDTO()

    private val shared = medKit(
        id = SHARED_KIT,
        name = "Дача",
        publication = MedKit.Publication.PUBLISHED,
        participantCount = 2
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

    /**
     * Заголовок спрашивает, а кнопка отвечает: одним словом их звать нельзя — человек не
     * различит, где вопрос, а где действие, и проверка не различит тоже.
     */
    @Test
    fun theTitleAsksAndTheButtonAnswers() {
        show(PackageTransferUiState(places = listOf(shared), isLoading = false))

        compose.onNodeWithText("Куда перенести").assertIsDisplayed()
        compose.onNodeWithText("Перенести").assertIsDisplayed()
    }

    @Test
    fun thePlacesAreListedWithWhatHelpsToTellThemApart() {
        show(PackageTransferUiState(places = listOf(home, shared), isLoading = false))

        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("Ванная").assertIsDisplayed()
        compose.onNodeWithText("Дача").assertIsDisplayed()
        compose.onNodeWithText("Общая аптечка").assertIsDisplayed()
    }

    /**
     * Переносить некуда — это рассказ, а не пустой экран: человеку говорят, что делать дальше.
     *
     * Красная проверка: показать пустой список — человек ищет, что нажать, и не находит.
     */
    @Test
    fun withNowhereToPutItTheScreenTellsWhatToDo() {
        show(PackageTransferUiState(places = emptyList(), isLoading = false))

        compose.onNodeWithText(
            "Переносить некуда: другой аптечки пока нет. Заведите вторую — и коробку будет куда положить."
        ).assertIsDisplayed()
        compose.onNodeWithText("Перенести").assertDoesNotExist()
    }

    /** Пока место не выбрано, нажимать нечего — и это честное гашение, а не отказ формы. */
    @Test
    fun untilAPlaceIsChosenThereIsNothingToPress() {
        show(PackageTransferUiState(places = listOf(shared), isLoading = false))

        compose.onNodeWithText("Перенести").assertIsNotEnabled()
    }

    @Test
    fun choosingAPlaceMakesTheActionAvailable() {
        show(PackageTransferUiState(places = listOf(shared), chosen = SHARED_KIT, isLoading = false))

        compose.onNodeWithText("Перенести").assertIsEnabled().performClick()

        assertEquals(1, transferred)
    }

    @Test
    fun tappingARowChoosesIt() {
        show(PackageTransferUiState(places = listOf(home, shared), isLoading = false))

        compose.onNodeWithText("Дача").performClick()

        assertEquals(SHARED_KIT, chosen)
    }

    @Test
    fun aRefusalIsSpelledOut() {
        show(
            PackageTransferUiState(
                places = listOf(shared),
                chosen = SHARED_KIT,
                refusal = TransferRefusal.TARGET_BUSY,
                isLoading = false
            )
        )

        compose.onNodeWithText("Выбранная аптечка ждёт ответа сервера — класть в неё пока рано.")
            .assertIsDisplayed()
    }
}
