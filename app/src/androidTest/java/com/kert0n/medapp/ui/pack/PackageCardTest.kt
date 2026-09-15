package com.kert0n.medapp.ui.pack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Упаковка в списке (PLAN H3 №4): человек ищет глазами лекарство, а не строку, — поэтому
 * сколько осталось и до какого срока стоят в карточке.
 *
 * Просрочка здесь — главное: она **не исчезает сама** и названа словом, а не одним цветом
 * (PLAN H3, REQ-026, REQ-027).
 */
@RunWith(AndroidJUnit4::class)
class PackageCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private var opened = 0

    private fun show(expiresOn: String?, medKitName: String? = null) {
        val card = pack(id = PACK, name = "Нурофен", quantity = tablets("20"), expiresOn = expiresOn?.let(::expiry))
            .projected()
            .toPresentationDTO()
        compose.setContent {
            MedAppTheme {
                PackageCard(
                    pack = card,
                    today = today,
                    onOpen = { opened++ },
                    medKitName = medKitName
                )
            }
        }
    }

    /** Первым — сколько есть и до какого срока: за этим человек в список и смотрит. */
    @Test
    fun howMuchAndUntilWhenAreBothInTheCard() {
        show(expiresOn = "2027-03-31")

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
        compose.onNodeWithText("Годен до 03.2027").assertIsDisplayed()
    }

    /**
     * Просрочка названа **словом**, а не одним цветом: цвета не видят ни в темноте, ни при
     * дальтонизме, ни экранным чтецом.
     *
     * Красная проверка: оставить у просроченной одну красную подложку — этой строки на экране не
     * окажется, и чтец о просрочке не скажет.
     */
    @Test
    fun anExpiredPackageIsNamedNotJustColoured() {
        show(expiresOn = "2025-03-31")

        compose.onNodeWithText("Просрочен 03.2025").assertIsDisplayed()
        compose.onNodeWithContentDescription("Есть просроченные упаковки").assertIsDisplayed()
    }

    /** Скоро истекающая предупреждает, не поднимая ложной тревоги: это не просрочка. */
    @Test
    fun oneThatExpiresSoonWarnsWithoutCryingWolf() {
        // Окно «скоро» — три дня (ExpiryDate.SOON_DAYS), и день внутри него берётся нарочно.
        show(expiresOn = "2026-09-17")

        compose.onNodeWithText("Истекает 17.09.2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Есть просроченные упаковки").assertDoesNotExist()
    }

    /** Неизвестный срок сказан вслух: молчание неотличимо от «ещё не прочитали». */
    @Test
    fun anUnknownExpiryIsSaidOutLoud() {
        show(expiresOn = null)

        compose.onNodeWithText("Срок не указан").assertIsDisplayed()
    }

    /** В списке всех лекарств строка называет свою аптечку. */
    @Test
    fun everywhereTheRowNamesItsShelf() {
        show(expiresOn = null, medKitName = "Дача")

        compose.onNodeWithText("Дача").assertIsDisplayed()
    }

    /** Нажимается вся карточка, а не одна её строка. */
    @Test
    fun theWholeCardIsTheButton() {
        show(expiresOn = null)

        compose.onNodeWithText("Срок не указан").performClick()

        assertEquals(1, opened)
    }
}
