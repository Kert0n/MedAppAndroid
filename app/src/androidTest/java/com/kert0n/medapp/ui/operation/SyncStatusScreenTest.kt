package com.kert0n.medapp.ui.operation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.operation.OutstandingOperationPresentationDTO
import com.kert0n.medapp.presentation.operation.SyncStatusUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Состояние синхронизации (PLAN H3 №28): что человек видит и что он может нажать. Что при этом
 * происходит с очередью, проверяет `OperationDismissingTest`.
 */
@RunWith(AndroidJUnit4::class)
class SyncStatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var refreshed = 0
    private var recounted: Uuid? = null
    private var dismissed: Uuid? = null

    private val pack = Uuid.random()

    private fun show(state: SyncStatusUiState) {
        compose.setContent {
            MedAppTheme {
                SyncStatusScreen(
                    state = state,
                    onRefresh = { refreshed++ },
                    onRecount = { recounted = it },
                    onDismiss = { dismissed = it },
                    onBack = {},
                    zone = ZoneOffset.UTC
                )
            }
        }
    }

    private fun waiting() = OutstandingOperationPresentationDTO(
        id = Uuid.random(),
        trouble = OutstandingOperationPresentationDTO.Trouble.WAITING,
        about = OutstandingOperationPresentationDTO.About.INTAKE,
        subject = "Нурофен",
        reason = null,
        retryAt = null,
        recountable = null
    )

    private fun refused(id: Uuid = Uuid.random()) = OutstandingOperationPresentationDTO(
        id = id,
        trouble = OutstandingOperationPresentationDTO.Trouble.REFUSED,
        about = OutstandingOperationPresentationDTO.About.PACKAGE_CHANGED,
        subject = "Ибупрофен",
        reason = OutstandingOperationPresentationDTO.Reason.CONFLICT,
        retryAt = null,
        recountable = pack
    )

    private fun unreadable(id: Uuid = Uuid.random()) = OutstandingOperationPresentationDTO(
        id = id,
        trouble = OutstandingOperationPresentationDTO.Trouble.UNREADABLE,
        about = OutstandingOperationPresentationDTO.About.UNKNOWN,
        subject = null,
        reason = OutstandingOperationPresentationDTO.Reason.UNREADABLE,
        retryAt = null,
        recountable = null
    )

    /**
     * Три вида строк различимы: ждущая говорит только, когда придём снова, и действий у неё нет;
     * отвергнутая называет причину; нечитаемую остаётся разобрать, и имени у неё нет — оно лежит
     * внутри строки, которую нечем прочитать.
     *
     * Красная проверка: показать все три одинаково — человек не поймёт, с какой из них что делать.
     */
    @Test
    fun threeKindsOfRowsAreToldApart() {
        show(SyncStatusUiState(rows = listOf(waiting(), refused(), unreadable()), isLoaded = true))

        compose.onNodeWithText("Приём: «Нурофен»").assertIsDisplayed()
        compose.onNodeWithText("Отправится, когда будет связь").assertIsDisplayed()
        compose.onNodeWithText("Изменение упаковки: «Ибупрофен»").assertIsDisplayed()
        compose.onNodeWithText("Коробку изменили раньше вас").assertIsDisplayed()
        compose.onNodeWithText("Эту строку нечем прочитать").assertIsDisplayed()
    }

    /** Расхождение по числу ведёт на пересчёт — той же коробки, о которой спор (REQ-045). */
    @Test
    fun aQuantityConflictLeadsToRecounting() {
        show(SyncStatusUiState(rows = listOf(refused()), isLoaded = true))

        compose.onNodeWithText("Пересчитать коробку").performClick()

        assertEquals(pack, recounted)
    }

    /** «Разобрал» убирает строку с экрана: решение о ней принято человеком. */
    @Test
    fun dismissingIsOfferedForWhatAwaitsADecision() {
        val id = Uuid.random()
        show(SyncStatusUiState(rows = listOf(unreadable(id)), isLoaded = true))

        compose.onNodeWithText("Разобрал").performClick()

        assertEquals(id, dismissed)
    }

    /** «Обновить» — заход целиком, а не отправка одной строки. */
    @Test
    fun refreshingAsksForAWholeRound() {
        show(SyncStatusUiState(rows = listOf(waiting()), isLoaded = true))

        compose.onNodeWithContentDescription("Обновить").performClick()

        assertEquals(1, refreshed)
    }

    /**
     * Связи нет — это «связи нет», а не ошибка: очередь цела, ей просто некуда ехать.
     *
     * Красная проверка: показать отказ с поводом «повторить» — человек жмёт его в самолёте и не
     * понимает, почему ничего не меняется.
     */
    @Test
    fun beingOfflineIsNotAnError() {
        show(SyncStatusUiState(rows = listOf(waiting()), isOffline = true, isLoaded = true))

        compose.onNodeWithText("Связи нет — очередь подождёт").assertIsDisplayed()
    }

    /** Пусто — «всё доехало», а не пустой список без объяснения. */
    @Test
    fun anEmptyQueueSaysEverythingArrived() {
        show(SyncStatusUiState(rows = emptyList(), refreshedAt = Instant.parse("2026-09-17T14:02:00Z"), isLoaded = true))

        compose.onNodeWithText("Всё доехало").assertIsDisplayed()
        compose.onNodeWithText("Последний обмен в 14:02").assertIsDisplayed()
    }
}
