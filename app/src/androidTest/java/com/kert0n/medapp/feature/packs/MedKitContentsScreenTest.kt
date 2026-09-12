package com.kert0n.medapp.feature.packs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства (№5). Два пустых состояния здесь разные:
 * пустая аптечка зовёт завести упаковку, неудачный поиск — сбросить запрос. Спутать их значит
 * предложить человеку не то.
 */
@RunWith(AndroidJUnit4::class)
class MedKitContentsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Дача")
    )

    private var left = false

    private lateinit var packages: FakePackages

    private fun show(medKitId: Uuid? = HOME_KIT, vararg packs: Package) {
        packages = FakePackages(*packs)
        val viewModel = MedKitContentsViewModel(
            packages = packages,
            medKits = medKits,
            removal = MedKitRemoval(medKits, packages, DirectTransactions),
            clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)
        )
        compose.setContent {
            MedAppTheme {
                MedKitContentsScreen(
                    medKitId = medKitId,
                    onBack = { left = true },
                    onOpen = {},
                    onAdd = {},
                    viewModel = viewModel
                )
            }
        }
    }

    @Test
    fun theListShowsWhatIsInside() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол", quantity = tablets("20")))

        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("Парацетамол").assertIsDisplayed()
        compose.onNodeWithText("20 таблетка").assertIsDisplayed()
    }

    @Test
    fun anEmptyMedKitInvitesToAddThings() {
        show(HOME_KIT)

        compose.onNodeWithText(
            "В этой аптечке пока ничего нет. Заведите первую упаковку — достаточно названия, количества и единицы."
        ).assertIsDisplayed()
    }

    /**
     * Красная проверка: показать одно и то же сообщение в обоих случаях — человеку предложат
     * завести упаковку там, где он просто неудачно искал, и случай краснеет.
     */
    @Test
    fun aFruitlessSearchOffersToResetNotToAdd() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))

        compose.onNodeWithText("Поиск лекарства").performTextInput("аспирин")

        compose.onNodeWithText("Ничего не нашлось. Попробуйте другое слово или сбросьте фильтр.")
            .assertIsDisplayed()
        compose.onNodeWithText("Сбросить").assertIsDisplayed()
    }

    @Test
    fun resettingBringsTheListBack() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))
        compose.onNodeWithText("Поиск лекарства").performTextInput("аспирин")

        compose.onNodeWithText("Сбросить").performClick()

        compose.onNodeWithText("Парацетамол").assertIsDisplayed()
    }

    /** Сузить список есть чем, и чем именно — видно сразу (PLAN H3 №4). */
    @Test
    fun thereIsSomethingToNarrowTheListWith() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))

        compose.onNodeWithText("Просроченные").assertIsDisplayed()
        compose.onNodeWithText("Истекают").assertIsDisplayed()
        compose.onNodeWithText("На курсе").assertIsDisplayed()
        // Полоса чипов прокручивается: порядок стоит за ними, и до него нужно доехать.
        compose.onNodeWithText("Порядок: по названию").performScrollTo().assertIsDisplayed()
    }

    /** На экране всех лекарств у каждой строки видно, из какой она аптечки. */
    @Test
    fun everywhereEachRowNamesItsMedKit() {
        show(
            null,
            pack(id = PACK, name = "Парацетамол"),
            pack(id = OTHER_PACK, name = "Нурофен", medKit = medKit(id = SHARED_KIT).ref)
        )

        compose.onNodeWithText("Все лекарства").assertIsDisplayed()
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("Дача").assertIsDisplayed()
    }

    /** С самой аптечкой тоже есть что делать, и делается это из её же экрана (PLAN H3). */
    @Test
    fun theMenuOffersToEditAndToRemove() {
        show(HOME_KIT)

        compose.onNodeWithContentDescription("Что можно с аптечкой").performClick()

        compose.onNodeWithText("Правка аптечки").assertIsDisplayed()
        compose.onNodeWithText("Убрать аптечку").assertIsDisplayed()
    }

    /** Пустую аптечку достаточно подтвердить: выбирать судьбу лекарств не из чего. */
    @Test
    fun anEmptyMedKitIsRemovedAfterOneConfirmation() {
        show(HOME_KIT)
        compose.onNodeWithContentDescription("Что можно с аптечкой").performClick()
        compose.onNodeWithText("Убрать аптечку").performClick()

        compose.onNodeWithText("В ней ничего нет — уйдёт только сама аптечка.").assertIsDisplayed()
        compose.onNodeWithText("Убрать").performClick()

        compose.waitForIdle()
        assertTrue(medKits.medKits.none { it.id == HOME_KIT })
        assertTrue(left)
    }

    /**
     * У непустой аптечки человек выбирает судьбу лекарств, и оба пути названы последствиями, а
     * не словом «удалить» (PLAN H3, ТЗ 4.1.1.2.3).
     *
     * Красная проверка: спросить одним «Удалить?» — человек не узнает, что лекарства можно
     * перенести, и случай краснеет.
     */
    @Test
    fun aFullMedKitAsksWhatToDoWithTheDrugs() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))
        compose.onNodeWithContentDescription("Что можно с аптечкой").performClick()
        compose.onNodeWithText("Убрать аптечку").performClick()

        compose.onNodeWithText("Перенести лекарства в другую аптечку").assertIsDisplayed()
        compose.onNodeWithText("Выбросить вместе с лекарствами").assertIsDisplayed()
        compose.onNodeWithText(
            "Упаковки и их история остатка исчезнут. Лечение и приёмы останутся."
        ).assertIsDisplayed()
    }

    @Test
    fun transferringMovesTheDrugsAndRemovesTheMedKit() {
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))
        compose.onNodeWithContentDescription("Что можно с аптечкой").performClick()
        compose.onNodeWithText("Убрать аптечку").performClick()

        compose.onNodeWithText("Перенести лекарства в другую аптечку").performClick()
        compose.onNodeWithText("Дача").performClick()
        compose.onNodeWithText("Перенести и убрать").performClick()

        compose.waitForIdle()
        assertEquals(SHARED_KIT, packages.packages.single().medKit.id)
        assertTrue(medKits.medKits.none { it.id == HOME_KIT })
    }

    /** Переносить некуда — так и сказано: молчащая кнопка объяснять не умеет. */
    @Test
    fun withNowhereToMoveTheDialogSaysSo() {
        medKits.forget(SHARED_KIT)
        show(HOME_KIT, pack(id = PACK, name = "Парацетамол"))
        compose.onNodeWithContentDescription("Что можно с аптечкой").performClick()
        compose.onNodeWithText("Убрать аптечку").performClick()

        compose.onNodeWithText("Переносить некуда: другой местной аптечки пока нет.")
            .assertIsDisplayed()
        compose.onNodeWithText("Перенести лекарства в другую аптечку").assertDoesNotExist()
    }
}
