package com.kert0n.medapp.ui.course

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.course.CourseEstimatePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcePresentationDTO
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
        fault: CourseSource.Fault? = null
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
        fault = fault
    )

    /** Строка говорит всё сразу: что за коробка, где лежит, сколько свободно и сколько выделено. */
    @Test
    fun aRowTellsTheBoxItsShelfAndItsAllocation() {
        show(CourseSourcesUiState(sources = listOf(source())))

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("Домашняя · свободно 20 таблетка").assertIsDisplayed()
        compose.onNodeWithText("выделено 3 приёма · 6 таблетка").assertIsDisplayed()
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

        compose.onNodeWithText("Отвязать").performClick()

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
        compose.onNodeWithText("Подключить ещё").performClick()
        assertEquals(1, added)
    }

    /** У законченного лечения источников не бывает: подключать и отвязывать нечего. */
    @Test
    fun aFinishedCourseOffersNoActions() {
        show(CourseSourcesUiState(sources = listOf(source()), isFinished = true))

        compose.onNodeWithText("Отвязать").assertDoesNotExist()
        compose.onNodeWithText("Подключить ещё").assertDoesNotExist()
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
        compose.onNodeWithText("Правка не записана: обеспечение пересчитается после «Сохранить».")
            .assertIsDisplayed()
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
