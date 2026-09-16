package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import android.view.WindowManager
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitSharingRefusal
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Поделиться аптечкой (PLAN H3 №20): что человек видит и что он может нажать. Что при этом
 * записывается, проверяет `MedKitSharingViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class MedKitSharingScreenTest {

    @get:Rule
    // Настоящая активность нужна одной проверке — запрету снимка экрана: флаг стоит на окне.
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var asked = 0
    private var published = 0
    private var invited = 0
    private var shown = 0
    private var hidden = 0

    private fun show(state: MedKitSharingUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitSharingScreen(
                    state = state,
                    onAsk = { asked++ },
                    onDismissAsking = {},
                    onPublish = { published++ },
                    onInvite = { invited++ },
                    onShowFullScreen = { shown++ },
                    onHideFullScreen = { hidden++ },
                    onBack = {}
                )
            }
        }
    }

    /**
     * Первым — цена решения, а не кнопка: что уедет, что останется и что доступ не отозвать,
     * человек читает **до** нажатия (PLAN E5).
     *
     * Красная проверка: показать одну кнопку «Сделать общей» — человек раздаёт доступ к своим
     * лекарствам, не узнав, что вернуть его нельзя.
     */
    @Test
    fun thePriceOfTheDecisionIsReadBeforeTheDecision() {
        show(MedKitSharingUiState.Deciding("Домашняя"))

        compose.onNodeWithText("Станет общим").assertIsDisplayed()
        compose.onNodeWithText("Останется у вас").assertIsDisplayed()
        compose.onNodeWithText(
            "Это необратимо: переданный доступ нельзя отозвать. Любой участник сможет удалить аптечку у всех."
        ).assertIsDisplayed()
    }

    /** Нажатие спрашивает, а не решает: сценарий зовёт только подтверждение. */
    @Test
    fun theButtonAsksAndDoesNotDecide() {
        show(MedKitSharingUiState.Deciding("Домашняя"))

        compose.onNodeWithText("Сделать общей").performClick()

        assertEquals(1, asked)
        assertEquals(0, published)
    }

    /** Подтверждение названо полкой: человек видит, какую именно он отдаёт. */
    @Test
    fun theConfirmationNamesTheShelfAndOnlyThenDecides() {
        show(MedKitSharingUiState.Deciding("Домашняя", isAsking = true))

        compose.onNodeWithText("Сделать «Домашняя» общей?").assertIsDisplayed()
        // «Сделать общей» на экране два — под последствиями и в самом вопросе; решает второе.
        compose.onAllNodesWithText("Сделать общей").onLast().performClick()

        assertEquals(1, published)
    }

    /**
     * Полка в пути приглашений не выдаёт: половины полки не бывает, и звать в неё рано
     * (PLAN D2, E5).
     */
    @Test
    fun aShelfOnItsWayOffersNoCode() {
        show(MedKitSharingUiState.OnItsWay("Домашняя"))

        compose.onNodeWithText("Решение об аптечке уже едет серверу. Пригласить можно будет, когда оно доедет.")
            .assertIsDisplayed()
        compose.onNodeWithText("Пригласить").assertDoesNotExist()
    }

    /** Код виден целиком, и срок при нём — оценкой, а не обещанием (PLAN B6). */
    @Test
    fun theCodeIsShownWithAnEstimatedTerm() {
        show(
            MedKitSharingUiState.Shared(
                name = "Семейная",
                invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30))
            )
        )

        compose.onNodeWithText("K7F-2M9-QX4").assertIsDisplayed()
        compose.onNodeWithText("Примерно до 15:30").assertIsDisplayed()
        compose.onNodeWithText("Обновить код").assertIsDisplayed()
    }

    /**
     * Отказ назван словами, и экран остаётся рабочим: человек повторяет, а не упирается в пустое
     * место.
     */
    @Test
    fun aRefusalIsSaidAndTheScreenKeepsWorking() {
        show(
            MedKitSharingUiState.Shared(
                name = "Семейная",
                refusal = MedKitSharingRefusal.Unavailable(Unavailability.NO_CONNECTION)
            )
        )

        compose.onNodeWithText("Нет связи с сервером. Проверьте подключение.").assertIsDisplayed()
        compose.onNodeWithText("Пригласить").performClick()

        assertEquals(1, asked)
    }

    /** Узор есть, и у него есть подпись: экранному чтецу картинка сама по себе не говорит ничего. */
    @Test
    fun theCodeIsAlsoAPatternWithAName() {
        show(shared())

        compose.onNodeWithContentDescription("Код приглашения узором").assertIsDisplayed()
        compose.onNodeWithText("Показать во весь экран").performClick()

        assertEquals(1, shown)
    }

    /**
     * Полный экран (№21) — состояние того же экрана: код на нём тот же, и закрывается он назад
     * к карточке, а не в новое место.
     */
    @Test
    fun theFullScreenShowsTheSameCodeAndClosesBack() {
        show(shared(isFullScreen = true))

        compose.onAllNodesWithText("K7F-2M9-QX4").onLast().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()

        assertEquals(1, hidden)
    }

    /**
     * Пока ключ на экране, снимок экрана запрещён (`FLAG_SECURE`, PLAN G3): ключ открывает чужую
     * аптечку, и оставлять его в галерее и в списке недавних нельзя.
     *
     * Красная проверка: не ставить запрет — ключ уезжает в снимок экрана и в предпросмотр недавних.
     */
    @Test
    fun theKeyOnScreenForbidsScreenshots() {
        show(shared())

        compose.waitForIdle()
        val flags = compose.activity.window.attributes.flags
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            flags and WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun shared(isFullScreen: Boolean = false) = MedKitSharingUiState.Shared(
        name = "Семейная",
        invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30)),
        isFullScreen = isFullScreen
    )
}
