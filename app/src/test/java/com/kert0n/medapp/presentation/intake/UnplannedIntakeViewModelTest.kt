package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.feature.notification.ReminderPromising
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.QuietNotificationSettings
import com.kert0n.medapp.fixture.UnaskedReminders
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.UnaskedIntakes
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Разовый приём с карточки коробки (PLAN H3 №10, D6): сколько человек взял и что ему отвечают.
 * Как это нарисовано, проверяет `UnplannedIntakeSheetTest`.
 */
class UnplannedIntakeViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages(pack(id = PACK, quantity = tablets("20"), defaultIntakeAmount = dose("2")))

    private val courses = FakeCourseStorage()

    /**
     * Приёмы, которые можно записать: подделка отвечает только на тот вопрос, о котором проверка,
     * — «факт записан», — а на остальные падает голосом `UnaskedIntakes`.
     */
    private class WritableIntakes : IntakeStorageRepository by UnaskedIntakes {
        val written = mutableListOf<IntakeOutcome>()
        override suspend fun record(outcome: IntakeOutcome): Boolean {
            written += outcome
            return true
        }
    }

    private val intakes = WritableIntakes()

    /**
     * Приём трогает лечения, державшие коробку: их зажимает владелец реакции. Здесь лечений нет,
     * и соседи сценария — подделки, которые падают, если их всё-таки позовут.
     */
    private val following = CourseFollowing(
        courses,
        packages,
        CourseCalendar(
            UnaskedIntakes,
            packages,
            ReminderPromising(UnaskedReminders, QuietNotificationSettings, DirectTransactions),
            ReminderWithdrawal(UnaskedReminders, DirectTransactions)
        ),
        QueueService(DirectTransactions, FakeQueue()),
        DirectTransactions
    )

    private fun viewModel() = UnplannedIntakeViewModel(
        recording = UnplannedIntakeRecording(
            intakes = intakes,
            courses = courses,
            packages = packages,
            following = following,
            queue = QueueService(DirectTransactions, FakeQueue()),
            transactions = DirectTransactions,
            clock = clock
        ),
        vocabulary = FakeVocabulary(),
        clock = clock,
        packages = packages,
        packageId = PACK
    )

    /**
     * Доза-подсказка стоит в поле готовым ответом: чаще всего человек её и подтверждает, а набирать
     * то же самое руками — лишняя работа (ТЗ 4.1.1.4.2).
     */
    @Test
    fun theHintOfTheBoxStandsInTheFieldReadyToBeConfirmed() {
        val model = viewModel()

        val state = watching(model.state) { it.awaiting { s -> !s.isLoading } }

        assertEquals("2", state.form.amount)
        assertEquals(TABLETS.name, state.unit?.name)
    }

    /** Подсказка — не правило: набранное человеком остаётся, что бы ни лежало в коробке. */
    @Test
    fun theHintIsOverwrittenByWhatThePersonTyped() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.edit(UnplannedIntakePresentationDTO("1.5"))
            state.awaiting { it.form.amount == "1.5" }
        }

        assertEquals("1.5", state.form.amount)
    }

    /** Приём записан — лист закрывается: человек сказал, что хотел, а число покажет карточка. */
    @Test
    fun aRecordedIntakeClosesTheSheet() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.record()
            state.awaiting { it.isRecorded }
        }

        assertNull(state.error)
        // Сколько человек принял, столько и уехало в сценарий; расход считает база, и о нём —
        // её проверки, а не эта.
        assertEquals(1, intakes.written.size)
        assertEquals(dose("2"), intakes.written.single().intake.taken?.amount)
    }

    /**
     * Второе нажатие второго приёма не пишет: приём — факт о мире, и повторить его человек не
     * просил (U1 «двойное нажатие зовёт сценарий один раз»).
     */
    @Test
    fun theSecondTapRecordsNothing() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.record()
            model.record()
            state.awaiting { it.isRecorded }
        }

        assertEquals(1, intakes.written.size)
    }

    /** В коробке меньше, чем взято: отказ назван словами, и ничего не записано (D6). */
    @Test
    fun takingMoreThanThereIsIsRefusedInWords() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.edit(UnplannedIntakePresentationDTO("50"))
            model.record()
            state.awaiting { it.error != null }
        }

        assertEquals(UnplannedIntakeError.Rejected(IntakeRejected.Reason.INSUFFICIENT), state.error)
        assertEquals(emptyList<Any>(), intakes.written)
    }

    /** Пустое поле — не «принял ноль»: ноля приёма не бывает, и сказано это разбором величины. */
    @Test
    fun anEmptyAmountIsNotZero() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.edit(UnplannedIntakePresentationDTO(""))
            model.record()
            state.awaiting { it.error != null }
        }

        assertEquals(UnplannedIntakeError.Amount(QuantityPresentationError.EMPTY), state.error)
    }
}
