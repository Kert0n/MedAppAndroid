package com.kert0n.medapp.ui.course

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.course.CourseEstimatePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcesMessage
import com.kert0n.medapp.presentation.course.CourseSourcesUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экран источников (PLAN H3 №16): что человек видит и что нажимает. Что при этом записывается,
 * проверяет `CourseSourcesViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class CourseSourcesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Ползунок узнаётся по тому, что он умеет: сдвинуть значение (`SetProgress`). */
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private var moved: Pair<Int, Int>? = null
    private val allocations = mutableListOf<Pair<Uuid, Int>>()
    private var detached: Uuid? = null
    private var confirmed = 0
    private var added = 0
    private var saved = 0

    private fun show(state: CourseSourcesUiState) {
        compose.setContent {
            MedAppTheme {
                CourseSourcesScreen(
                    state = state,
                    onMove = { from, to -> moved = from to to },
                    onAllocate = { id, doses -> allocations += id to doses },
                    onDetach = { detached = it },
                    onConfirmDetach = { confirmed++ },
                    onDismissDetach = {},
                    onDismissMessage = {},
                    onAdd = { added++ },
                    onSave = { saved++ },
                    onBack = {}
                )
            }
        }
    }

    private fun source(
        packageId: Uuid = PACK,
        name: String = "Нурофен",
        allocatedDoses: Int = 3,
        maxDoses: Int? = 7,
        fault: CourseSource.Fault? = null,
        status: PackageStatus = PackageStatus.ACTIVE
    ) = CourseSourcePresentationDTO(
        packageId = packageId,
        name = name,
        medKitName = "Домашняя",
        expiresOn = null,
        availableToMe = QuantityPresentationDTO("20", TABLETS.toPresentationDTO()),
        allocatedDoses = allocatedDoses,
        allocatedAmount = QuantityPresentationDTO("6", TABLETS.toPresentationDTO()),
        coveredDoses = 3,
        maxDoses = maxDoses,
        fault = fault,
        status = status
    )

    /**
     * Решение о коробке принято, а сервер ещё не ответил: приёмы из неё пропали, и строка говорит
     * почему. Без этого нехватка, взявшаяся из ниоткуда, читается как ошибка приложения
     * (замечание владельца 2026-09-17, PLAN D4).
     */
    @Test
    fun aBoxWhoseFateIsDecidedSaysSoInTheRow() {
        show(CourseSourcesUiState(sources = listOf(source(status = PackageStatus.REMOVING))))

        compose.onNodeWithText("Удаление в пути").assertIsDisplayed()
        // Выделение только названо: двигать его у коробки, которой вот-вот не станет, некуда.
        compose.onNode(slider).assertDoesNotExist()
        // «Свободно 0» сказало бы, что коробка пустая; пустой она не стала.
        compose.onNodeWithText("свободно", substring = true).assertDoesNotExist()
        compose.onNodeWithText("выделено 3 приёма", substring = true).assertIsDisplayed()
    }

    /** Из аптечки вышли — коробка не наша, и слова те же, что на её списке. */
    @Test
    fun aBoxOfAShelfWeLeftSaysSoInTheRow() {
        show(CourseSourcesUiState(sources = listOf(source(status = PackageStatus.LOST))))

        compose.onNodeWithText("Коробка не у нас").assertIsDisplayed()
    }

    /** Строка говорит всё сразу: что за коробка, где лежит, сколько свободно и сколько выделено. */
    @Test
    fun aRowTellsTheBoxItsShelfAndItsAllocation() {
        show(CourseSourcesUiState(sources = listOf(source())))

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithText("свободно 20 таблетка").assertIsDisplayed()
        compose.onNodeWithText("выделено 3 приёма · 6 таблетка").assertIsDisplayed()
    }

    /**
     * Коробка, в которой нет ни одной целой дозы, говорит **какой** дозы не хватает. Без этого
     * строка «свободно 20 шт» рядом с молчаливым «целой дозы не наберётся» читается как ошибка
     * приложения: человек не видит, что доза — 21 шт, а доза берётся из одной коробки (C1).
     */
    @Test
    fun aBoxTooSmallForASingleDoseNamesTheDose() {
        show(
            CourseSourcesUiState(
                dose = QuantityPresentationDTO("21", TABLETS.toPresentationDTO()),
                sources = listOf(source(allocatedDoses = 0, maxDoses = 0))
            )
        )

        compose.onNodeWithText("Одной дозы (21 таблетка) здесь не наберётся: доза берётся из одной коробки.")
            .assertIsDisplayed()
    }

    /**
     * Набранное в поле число уезжает **сразу**, а не когда человек уйдёт из поля. Иначе «Сохранить»
     * пишет прежний состав: палец с поля на кнопку фокус не уводит, и набранные приёмы пропадают
     * молча — это нашла история Ирины (PLAN U1 «история человека»).
     */
    @Test
    fun aTypedNumberLeavesTheFieldAtOnce() {
        show(CourseSourcesUiState(sources = listOf(source(allocatedDoses = 0, maxDoses = 10))))

        compose.onNode(hasSetTextAction()).performTextReplacement("7")

        assertEquals(listOf(PACK to 7), allocations)
    }

    /** Отключённый источник объясняет себя словами, а не одним цветом. */
    @Test
    fun aFaultedSourceSaysWhatHappened() {
        show(CourseSourcesUiState(sources = listOf(source(fault = CourseSource.Fault.UNIT_MISMATCH))))

        compose.onNodeWithText("Другая единица — снимите пачку или поправьте её сведения.").assertIsDisplayed()
    }

    /** «Отвязать» у идущего лечения только спрашивает: сценарий зовётся после ответа человека. */
    @Test
    fun detachingAsksBeforeItDoesAnything() {
        show(CourseSourcesUiState(sources = listOf(source())))

        compose.onNodeWithContentDescription("Отвязать").performClick()

        assertEquals(PACK, detached)
        assertEquals(0, confirmed)
    }

    /** Ответив на вопрос, человек подтверждает отвязку — и только тогда она уходит в сценарий. */
    @Test
    fun theQuestionIsAnsweredByTheSameWord() {
        show(CourseSourcesUiState(sources = listOf(source()), asksToDetach = PACK))

        compose.onNodeWithText("Отвязать пачку?").assertIsDisplayed()
        compose.onNodeWithText("Лечение останется, но эта коробка перестанет его обеспечивать, а её бронь снимется.")
            .assertIsDisplayed()
    }

    /**
     * Ответ «Отвязать» доходит до сценария. Без этой проверки отключённый обработчик диалога
     * незаметен: тексты на месте, а нажатие не делает ничего. Кнопка берётся из диалога — тем же
     * словом названо и действие в строке источника.
     */
    @Test
    fun theAnswerReachesTheScenario() {
        show(CourseSourcesUiState(sources = listOf(source()), asksToDetach = PACK))

        compose.onNode(hasText("Отвязать") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(1, confirmed)
    }

    /**
     * Исход «Сохранить» виден и тогда, когда список опустел: отвязав последний источник, человек
     * иначе остаётся с пустым экраном, а отказ записи пропадает вместе со списком — вместе с
     * «Понятно», которым его снимают (C1 «Исход сценария доходит при любом состоянии экрана»).
     */
    @Test
    fun theOutcomeIsSeenOnAnEmptiedScreen() {
        show(CourseSourcesUiState(sources = emptyList(), message = CourseSourcesMessage.Stale))

        compose.onNodeWithText("Источники правили с другого экрана — список перечитан, повторите.").assertIsDisplayed()
        compose.onNodeWithText("Понятно").assertIsDisplayed()
    }

    /** Ручка перестановки названа экранному чтецу: жест ему недоступен, а порядок менять нужно. */
    @Test
    fun theHandleIsNamedForAScreenReader() {
        show(CourseSourcesUiState(sources = listOf(source(), source(OTHER_PACK, "Ибупрофен"))))

        compose.onNodeWithContentDescription("Переставить: Нурофен").assertIsDisplayed()
        compose.onNodeWithContentDescription("Переставить: Ибупрофен").assertIsDisplayed()
    }

    /** Пусто — это не отказ: сказано, что делать, и кнопка одна. */
    @Test
    fun anEmptyStackInvitesToAttachTheFirstBox() {
        show(CourseSourcesUiState())

        compose.onNodeWithText("Пачек пока нет — подключите первую.").assertIsDisplayed()
        compose.onNodeWithText("Подключить ещё коробку").performClick()
        assertEquals(1, added)
    }

    /** У законченного лечения источников не бывает: подключать и отвязывать нечего. */
    @Test
    fun aFinishedCourseOffersNoActions() {
        show(CourseSourcesUiState(sources = listOf(source()), isFinished = true))

        compose.onNodeWithContentDescription("Отвязать").assertDoesNotExist()
        compose.onNodeWithText("Подключить ещё коробку").assertDoesNotExist()
    }

    /**
     * Во время движения ползунка не зовётся ничего, по отпусканию — ровно один раз (C1
     * «Ползунок»): иначе каждая дрожь пальца была бы отдельным решением человека.
     */
    @Test
    fun theSliderSaysNothingWhileItMovesAndOnceWhenReleased() {
        show(CourseSourcesUiState(sources = listOf(source())))

        compose.onNode(slider).performTouchInput { down(center); moveBy(Offset(120f, 0f)) }
        assertEquals(emptyList<Pair<Uuid, Int>>(), allocations)

        compose.onNode(slider).performTouchInput { up() }
        assertEquals(1, allocations.size)
        assertEquals(PACK, allocations.single().first)
    }

    /** Без предела выделять нечего — сказано словами, а не показан ползунок, который не двигается. */
    @Test
    fun withoutALimitThereIsNoSliderButThereAreWords() {
        show(CourseSourcesUiState(sources = listOf(source(maxDoses = null))))

        compose.onNodeWithText("Укажите дозу и число приёмов — тогда будет что выделять.").assertIsDisplayed()
        compose.onAllNodes(slider).assertCountEquals(0)
    }

    /**
     * Пока правка не записана, экран говорит **что получится** — и просит сохранить. Сводка
     * записанного обеспечения в это время не показывается: она о прежнем составе.
     */
    @Test
    fun anUnsavedStackShowsWhatItWouldGiveAndAsksToSave() {
        show(
            CourseSourcesUiState(
                sources = listOf(source()),
                estimate = CourseEstimatePresentationDTO(requiredDoses = 10, coveredDoses = 3, missingDoses = 7),
                hasUnsavedChanges = true
            )
        )

        compose.onNodeWithText("нужно 10 приёмов · обеспечено 3").assertIsDisplayed()
        compose.onNodeWithText("Не хватает 7 приёмов").assertIsDisplayed()
        compose.onNodeWithText("С правкой").assertIsDisplayed()
    }

    /** «Сохранить» уносит собранный состав одним решением. */
    @Test
    fun savingIsOnePress() {
        show(CourseSourcesUiState(sources = listOf(source()), hasUnsavedChanges = true))

        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /** Лечения больше нет — это отказ, а не пустой стек. */
    @Test
    fun aMissingCourseSaysSo() {
        show(CourseSourcesUiState(isGone = true))

        compose.onNodeWithText("Этого лечения больше нет.").assertIsDisplayed()
    }
}
