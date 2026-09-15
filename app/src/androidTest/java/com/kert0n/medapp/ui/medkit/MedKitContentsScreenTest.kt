package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Содержимое аптечки и все лекарства (PLAN H3 №4, №5). */
@RunWith(AndroidJUnit4::class)
class MedKitContentsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var reset = 0
    private var added = 0
    private var opened: Uuid? = null

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private val home = medKit(id = HOME_KIT, name = "Домашняя")
        .projection(MedKitContents(packages = 2, expired = 0)).toPresentationDTO()

    private fun row(id: Uuid, name: String, expiresOn: String? = null): PackagePresentationDTO =
        pack(id = id, name = name, expiresOn = expiresOn?.let(::expiry)).projected().toPresentationDTO()

    private fun show(state: MedKitContentsUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitContentsScreen(
                    state = state,
                    onBack = {},
                    onOpen = { opened = it },
                    onAdd = { added++ },
                    onEdit = {},
                    onSearch = {},
                    onNarrow = {},
                    onOrder = {},
                    onReset = { reset++ },
                    onAskToRemove = {},
                    onPickTarget = {},
                    onRemove = {},
                    onDismissRemoval = {}
                )
            }
        }
    }

    private fun contents(
        packages: List<PackagePresentationDTO> = listOf(row(PACK, "Нурофен")),
        text: String = "",
        everywhere: Boolean = false,
        placeNames: Map<Uuid, String> = emptyMap(),
        removing: RemovalStep? = null
    ) = MedKitContentsUiState(
        medKit = if (everywhere) null else home,
        isEverywhere = everywhere,
        packages = packages,
        placeNames = placeNames,
        others = listOf(medKit(id = SHARED_KIT, name = "Дача").projection(MedKitContents.EMPTY).toPresentationDTO()),
        text = text,
        today = today,
        isLoaded = true,
        removing = removing
    )

    /** Область видно по заголовку и по подписи поиска, а не по отдельному экрану. */
    @Test
    fun theAreaIsVisibleInTheTitleAndTheSearch() {
        show(contents())

        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("Поиск по аптечке").assertIsDisplayed()
    }

    @Test
    fun allMedicinesSaySoAndSearchEverywhere() {
        show(contents(everywhere = true, placeNames = mapOf(HOME_KIT to "Домашняя")))

        compose.onNodeWithText("Все лекарства").assertIsDisplayed()
        compose.onNodeWithText("Поиск по всем аптечкам").assertIsDisplayed()
    }

    /** На экране всех лекарств каждая строка называет свою аптечку. */
    @Test
    fun everywhereEachRowNamesItsShelf() {
        show(contents(everywhere = true, placeNames = mapOf(HOME_KIT to "Домашняя")))

        compose.onNodeWithText("Домашняя").assertIsDisplayed()
    }

    /**
     * «Здесь ничего нет» и «ничего не нашлось» — разные сообщения, и второе предлагает сброс, а
     * не «завести упаковку».
     *
     * Красная проверка: одно сообщение на оба случая — человеку предложат не то.
     */
    @Test
    fun anEmptyShelfInvitesToAddAPackage() {
        show(contents(packages = emptyList()))

        compose.onNodeWithText("Здесь пока ничего нет.").assertIsDisplayed()
        compose.onNodeWithText("Завести упаковку").performClick()

        assertEquals(1, added)
    }

    @Test
    fun anEmptySearchInvitesToReset() {
        show(contents(packages = emptyList(), text = "такого нет"))

        compose.onNodeWithText("Ничего не нашлось.").assertIsDisplayed()
        compose.onNodeWithText("Сбросить").performClick()

        assertEquals(1, reset)
        assertEquals(0, added)
    }

    /** Во всех аптечках пусто — это третье сообщение: заводить отсюда нечего. */
    @Test
    fun emptyEverywhereOffersNothingToPress() {
        show(contents(packages = emptyList(), everywhere = true))

        compose.onNodeWithText("Во всех аптечках пока пусто.").assertIsDisplayed()
        compose.onNodeWithText("Завести упаковку").assertDoesNotExist()
    }

    /** Чем сузить — видно сразу, и фильтров несколько, а выбирается один. */
    @Test
    fun whatToNarrowByIsInSight() {
        show(contents())

        compose.onNodeWithText("Просроченные").assertIsDisplayed()
        // Полоса сужений прокручивается вбок: сортировка стоит за фильтрами и до неё доезжают.
        compose.onNodeWithText("Сортировка: по названию").performScrollTo().assertIsDisplayed()
    }

    /** Нажимается вся карточка, а не одна её строка. */
    @Test
    fun theWholeRowIsTheButton() {
        show(contents(packages = listOf(row(PACK, "Нурофен"))))

        compose.onNodeWithText("20 таблетка").performClick()

        assertEquals(PACK, opened)
    }

    /** У всех лекарств хозяина нет: править и убирать там нечего. */
    @Test
    fun allMedicinesHaveNoShelfToManage() {
        show(contents(everywhere = true))

        compose.onNodeWithContentDescription("Что можно с аптечкой").assertDoesNotExist()
    }

    /** Уборка непустой полки спрашивает, что делать с лекарствами, а не просто «удалить». */
    @Test
    fun removingAFullShelfAsksAboutTheMedicines() {
        show(contents(removing = RemovalStep.ASKING))

        compose.onNodeWithText("Убрать «Домашняя»?").assertIsDisplayed()
        compose.onNodeWithText("Внутри 2 упаковок. Решите, что с ними делать.").assertIsDisplayed()
        compose.onNodeWithText("Перенести лекарства в другую аптечку").assertIsDisplayed()
        compose.onNodeWithText("Выбросить вместе с лекарствами").assertIsDisplayed()
    }

    @Test
    fun theTargetIsChosenByName() {
        show(contents(removing = RemovalStep.PICKING_TARGET))

        compose.onNodeWithText("Куда перенести лекарства").assertIsDisplayed()
        compose.onNodeWithText("Дача").assertIsDisplayed()
    }

    /**
     * Просроченная стоит первой и при любой сортировке, и экран её не переставляет: порядок —
     * свойство чтения (PLAN H4, REQ-026), а список рисует то, что ему дали.
     *
     * Красная проверка: отсортировать список на экране — просроченная уедет вниз к своей букве.
     */
    @Test
    fun theOrderIsTheOneTheReadingGave() {
        show(
            contents(
                packages = listOf(
                    row(PACK, "Ярлык", expiresOn = "2025-03-31"),
                    row(OTHER_PACK, "Аспирин")
                )
            )
        )

        val shown = compose.onAllNodesWithText("Просрочен 03.2025").fetchSemanticsNodes()
        assertEquals(1, shown.size)
        // Просроченная «Ярлык» нарисована выше «Аспирина», хотя по алфавиту была бы ниже.
        val expired = compose.onNodeWithText("Ярлык").fetchSemanticsNode().positionInRoot.y
        val other = compose.onNodeWithText("Аспирин").fetchSemanticsNode().positionInRoot.y
        assertTrue("просроченная уехала вниз", expired < other)
    }

    /** Просроченная не исчезает сама: без фильтра она всё равно в списке (REQ-027). */
    @Test
    fun anExpiredPackageDoesNotVanishOnItsOwn() {
        show(contents(packages = listOf(row(PACK, "Ярлык", expiresOn = "2025-03-31"))))

        compose.onNodeWithText("Ярлык").assertIsDisplayed()
        compose.onNodeWithText("Просрочен 03.2025").assertIsDisplayed()
    }
}
