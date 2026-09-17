package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.report.FutureRowPresentationDTO
import com.kert0n.medapp.presentation.report.FutureSpendingPresentationDTO
import com.kert0n.medapp.presentation.report.HorizonPreset
import com.kert0n.medapp.presentation.report.PeriodPreset
import com.kert0n.medapp.presentation.report.ReportBarPresentationDTO
import com.kert0n.medapp.presentation.report.ReportGroupPresentationDTO
import com.kert0n.medapp.presentation.report.ReportsUiState
import com.kert0n.medapp.presentation.report.SpendingPresentationDTO
import com.kert0n.medapp.presentation.report.SpentBoxRowPresentationDTO
import com.kert0n.medapp.presentation.report.SpentEpisodeRowPresentationDTO
import com.kert0n.medapp.presentation.report.StockSummaryPresentationDTO
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Место «Отчёты» (PLAN H3 «Набор аналитики», экран 26): что человек видит и что нажимает. Откуда
 * берутся числа, проверяют мапперы и `ReportsViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2027, 3, 10)
    private val pieces = UnitPresentationDTO(Uuid.random(), "шт")
    private val course = Uuid.random()
    private var opened: Uuid? = null
    private var preset: HorizonPreset? = null
    private var period: PeriodPreset? = null

    private fun quantity(amount: String) = QuantityPresentationDTO(amount, pieces)

    private val summary = StockSummaryPresentationDTO(
        packages = 4,
        byCategory = listOf(ReportBarPresentationDTO("Обезболивающие", 3, 0.75f), ReportBarPresentationDTO(null, 1, 0.25f)),
        byForm = listOf(ReportBarPresentationDTO("таблетки", 4, 1f)),
        prices = listOf(MoneyPresentationDTO("320", "RUB")),
        unpriced = 3
    )

    private val future = FutureSpendingPresentationDTO(
        today = today,
        until = LocalDate.of(2027, 4, 10),
        preset = HorizonPreset.MONTH,
        groups = listOf(
            ReportGroupPresentationDTO(
                quantity("20"),
                listOf(FutureRowPresentationDTO(course, "Нурофен от спины", 20, quantity("20"), 1f))
            )
        )
    )

    private val spent = SpendingPresentationDTO(
        from = LocalDate.of(2027, 2, 10),
        to = today,
        today = today,
        preset = PeriodPreset.MONTH,
        episodes = listOf(
            ReportGroupPresentationDTO(
                quantity("5"),
                listOf(SpentEpisodeRowPresentationDTO(course, "Нурофен от спины", quantity("5"), 5, 1f))
            )
        ),
        boxes = listOf(
            ReportGroupPresentationDTO(
                quantity("1"),
                listOf(SpentBoxRowPresentationDTO("Цетрин", quantity("1"), 1, 1f))
            )
        )
    )

    private fun show(
        state: ReportsUiState,
        mode: ReportsMode = ReportsMode.SUMMARY,
        scale: Float = 1f
    ) {
        compose.setContent {
            Screen(state, mode, scale)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: ReportsUiState, initial: ReportsMode, scale: Float = 1f) {
        // Режим держит оболочка через `rememberSaveable` — здесь так же, иначе проверять
        // переживание смерти процесса было бы нечему.
        var mode by rememberSaveable { mutableStateOf(initial) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            MedAppTheme {
                ReportsScreen(
                    state = state,
                    mode = mode,
                    onMode = { mode = it },
                    onHorizonPreset = { preset = it },
                    onHorizonUntil = {},
                    onPeriodPreset = { period = it },
                    onPeriod = { _, _ -> },
                    onCourse = { opened = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    /** Пока чтение не пришло, экран ждёт — и говорит об этом экранному чтецу. */
    @Test
    fun anUnreadReportWaits() {
        show(ReportsUiState())

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
    }

    /**
     * **Пусто у каждого отчёта своё.** Нет коробок, нечего израсходовать и не было приёмов — три
     * разные новости, и общим «пусто» их не заменить: человек иначе не поймёт, чего ему не хватает.
     */
    @Test
    fun everyReportHasItsOwnEmptyWords() {
        show(
            ReportsUiState(
                summary = ScreenState.Ready(StockSummaryPresentationDTO(0, emptyList(), emptyList(), emptyList(), 0)),
                future = ScreenState.Ready(future.copy(groups = emptyList())),
                spent = ScreenState.Ready(spent.copy(episodes = emptyList(), boxes = emptyList()))
            )
        )

        compose.onNodeWithText("Упаковок пока нет").assertIsDisplayed()
        compose.onNodeWithText("Расход").performClick()
        compose.onNodeWithText("Идущих лечений на этот срок нет").assertIsDisplayed()
        compose.onNodeWithText("Истрачено").performClick()
        compose.onNodeWithText("За этот период приёмов не было").assertIsDisplayed()
    }

    /** Сводка: число упаковок, цена по валютам и пачки без цены — отдельной строкой, а не нулём. */
    @Test
    fun theSummaryTellsWhatIsThereAndWhatIsUnknown() {
        show(ReportsUiState(summary = ScreenState.Ready(summary)))

        compose.onNodeWithText("4 упаковки").assertIsDisplayed()
        compose.onNodeWithText("320 ₽").assertIsDisplayed()
        compose.onNodeWithText("У 3 упаковок цена не указана").assertIsDisplayed()
        compose.onNodeWithText("Не указано").assertIsDisplayed()
    }

    /**
     * Число полосы стоит **текстом**: длина и цвет — не единственные носители (PLAN H3 «Дизайн»),
     * и человеку, читающему экран на слух, полоса не говорит ничего.
     */
    @Test
    fun everyBarSaysItsNumberInWords() {
        show(ReportsUiState(summary = ScreenState.Ready(summary)))

        compose.onNode(hasText("Обезболивающие", substring = true) and hasText("3", substring = true)).assertIsDisplayed()
    }

    /** Строка лечения ведёт на его карточку; строка разового приёма никуда не ведёт (PLAN C1). */
    @Test
    fun aTreatmentRowLeadsToItsCardAndABoxRowDoesNot() {
        show(ReportsUiState(spent = ScreenState.Ready(spent)), mode = ReportsMode.SPENT)

        compose.onNode(hasClickAction() and hasText("Нурофен от спины", substring = true)).performClick()
        assertEquals(course, opened)
        compose.onNode(hasText("Цетрин", substring = true)).assertHasNoClickAction()
    }

    /** Срок выбирается нажатием на чип, и выбор доходит до модели. */
    @Test
    fun aChipChoosesThePeriod() {
        show(ReportsUiState(future = ScreenState.Ready(future)), mode = ReportsMode.FUTURE)

        compose.onNodeWithText("Неделя").performClick()

        assertEquals(HorizonPreset.WEEK, preset)
    }

    /** Отказ чтения показан отказом, а не вечным ожиданием: у кружка человеку нечего ждать. */
    @Test
    fun aFailedReadingIsToldAsFailure() {
        show(ReportsUiState(summary = ScreenState.Failed(Unavailability.DEVICE_STORAGE)))

        compose.onNodeWithText("Не удалось сохранить данные на устройстве.").assertIsDisplayed()
    }

    /** Крупный шрифт не режет ни переключателя отчётов, ни чипов срока: они переносятся строкой. */
    @Test
    fun largeFontKeepsEveryChoiceReadable() {
        show(ReportsUiState(spent = ScreenState.Ready(spent)), mode = ReportsMode.SPENT, scale = 1.3f)

        compose.onNodeWithText("Истрачено").assertIsDisplayed()
        compose.onNodeWithText("3 месяца").assertIsDisplayed()
        compose.onNodeWithText("Свой период").assertIsDisplayed()
    }

    /** У значков есть имя для экранного чтеца там, где они говорят что-то своё. */
    @Test
    fun iconsAreNamedForTheScreenReader() {
        show(ReportsUiState(summary = ScreenState.Ready(summary)))

        compose.onNodeWithContentDescription("Общая цена").assertIsDisplayed()
    }

    /** Выбранный отчёт переживает смерть процесса: человек возвращается туда, где стоял. */
    @Test
    fun theChosenReportSurvivesProcessDeath() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen(ReportsUiState(spent = ScreenState.Ready(spent)), ReportsMode.SUMMARY) }

        compose.onNodeWithText("Истрачено").performClick()
        compose.onNodeWithText("С 10.02.2027 по 10.03.2027").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("С 10.02.2027 по 10.03.2027").assertIsDisplayed()
    }
}
