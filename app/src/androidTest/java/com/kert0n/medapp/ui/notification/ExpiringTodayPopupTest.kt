package com.kert0n.medapp.ui.notification

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.notification.ExpiringTodayUiState
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Попап «сегодня истекает» (PLAN D8, H3 «Уведомления на экране»): что человек видит и чем
 * закрывает. Когда он приходит и что отмечает, проверяет `ExpiringTodayTest`.
 */
@RunWith(AndroidJUnit4::class)
class ExpiringTodayPopupTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Uuid>()
    private var dismissed = 0

    private fun show(state: ExpiringTodayUiState) {
        compose.setContent {
            MedAppTheme {
                ExpiringTodayPopup(state = state, onOpenPackage = { opened += it }, onDismiss = { dismissed++ })
            }
        }
    }

    private fun expiring() = ExpiringTodayUiState(
        boxes = listOf(
            pack(
                id = PACK,
                name = "Нурофен",
                quantity = tablets("20"),
                form = TABLET_FORM,
                expiresOn = ExpiryDate(LocalDate.of(2027, 3, 10))
            ).projected().toPresentationDTO()
        )
    )

    /** Коробка названа, и сказано, до какого дня она годна: это и есть новость. */
    @Test
    fun theBoxAndItsLastDayAreBothOnTheCard() {
        show(expiring())

        compose.onNodeWithText("Сегодня истекает срок годности").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка · годен до 10.03.2027").assertIsDisplayed()
    }

    /**
     * Нажатие на карточку ведёт к коробке и **не** закрывает попап: это тот же разговор, и
     * возврат с карточки его не обрывает (решение владельца 2026-09-16).
     */
    @Test
    fun tappingTheCardLeadsToTheBoxWithoutClosingThePopup() {
        show(expiring())

        compose.onNodeWithText("Нурофен").performClick()

        assertEquals(listOf(PACK), opened)
        assertEquals(0, dismissed)
    }

    /** Закрывает только крестик: подтверждать новость нечем, и кнопки «понятно» у неё нет. */
    @Test
    fun onlyTheCrossCloses() {
        show(expiring())

        compose.onNodeWithContentDescription("Закрыть").performClick()

        assertEquals(1, dismissed)
    }

    /** Говорить не о чем — попапа нет: пустой диалог человек читает как поломку. */
    @Test
    fun anEmptyPopupDoesNotShowAtAll() {
        show(ExpiringTodayUiState())

        compose.onNodeWithText("Сегодня истекает срок годности").assertDoesNotExist()
    }
}
