package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderPromising
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SettingsChanging
import com.kert0n.medapp.feature.settings.SyncInterval
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeDailySchedule
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeSettingsStore
import com.kert0n.medapp.fixture.FakeSyncOperations
import com.kert0n.medapp.fixture.FakeSyncSchedule
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.QuietNotificationSettings
import com.kert0n.medapp.fixture.UnaskedIntakes
import com.kert0n.medapp.fixture.UnaskedReminders
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.watching
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Форма настроек (PLAN H3 №27): что записывается и что человек видит в ответ. Как настройки
 * применяются, проверяет `SettingsChangingTest`; здесь сверка не зовётся — правки не трогают
 * уведомлений, и порты обязательств стоят неспрашиваемыми.
 */
class SettingsViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val store = FakeSettingsStore()
    private val sync = FakeSyncSchedule()

    private fun viewModel(): SettingsViewModel {
        val reconciliation = NotificationReconciliation(
            UnaskedIntakes, FakePackages(), FakeCourses(), UnaskedReminders, FakeSyncOperations(),
            ReminderPromising(UnaskedReminders, QuietNotificationSettings, DirectTransactions),
            ReminderWithdrawal(UnaskedReminders, DirectTransactions),
            QuietNotificationSettings, DirectTransactions
        )
        val changing = SettingsChanging(
            store, sync, FakeDailySchedule(), reconciliation,
            Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)
        )
        return SettingsViewModel(store, changing)
    }

    /** Форма открывается записанным, а не умолчаниями. */
    @Test
    fun theFormOpensWithWhatIsWritten() {
        store.saved = AppSettings(syncInterval = SyncInterval(240))
        val model = viewModel()

        val state = watching(model.state) { it.awaiting { s -> s is SettingsUiState.Editing } }

        assertEquals(240L, (state as SettingsUiState.Editing).form.syncIntervalMinutes)
    }

    /**
     * Ввод человека не затирается значением, дочитанным из хранилища: форма прочла записанное
     * один раз, и новое значение потока — не его правка (U1).
     */
    @Test
    fun aLaterStoredValueDoesNotOverwriteTyping() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it is SettingsUiState.Editing }
            model.edit((state.value as SettingsUiState.Editing).form.copy(snoozeMinutes = "25"))
            store.saved = AppSettings(syncInterval = SyncInterval(480))
            state.value
        }

        assertEquals("25", (state as SettingsUiState.Editing).form.snoozeMinutes)
        assertEquals(60L, state.form.syncIntervalMinutes)
    }

    /** Двойное «сохранить» зовёт сценарий один раз: иначе вторая запись перечитывала бы и пересоздавала задачи зря. */
    @Test
    fun savingTwiceWritesOnce() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it is SettingsUiState.Editing }
            model.save()
            model.save()
            state.awaiting { it is SettingsUiState.Editing && it.isSaved }
        }

        assertEquals(1, store.saves)
    }

    /** Записанный интервал обмена уходит планировщику: иначе фоновый заход шёл бы по-старому. */
    @Test
    fun theSavedIntervalReachesTheScheduler() {
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { it is SettingsUiState.Editing }
            model.edit((state.value as SettingsUiState.Editing).form.copy(syncIntervalMinutes = 15))
            model.save()
            state.awaiting { it is SettingsUiState.Editing && it.isSaved }
        }

        assertEquals(listOf(SyncInterval(15)), sync.kept)
    }

    /** Отказ разбора называет поле и ничего не пишет. */
    @Test
    fun aRejectedFieldIsNamedAndNothingIsWritten() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it is SettingsUiState.Editing }
            model.edit((state.value as SettingsUiState.Editing).form.copy(coverageThresholdDays = "-3"))
            model.save()
            state.value
        }

        assertEquals(SettingsFormError.Input.THRESHOLD_NEGATIVE, (state as SettingsUiState.Editing).error)
        assertEquals(0, store.saves)
    }

    /** Не легло — сказано, а ввод цел: человек нажмёт ещё раз, а не наберёт всё заново. */
    @Test
    fun aLostWriteIsToldAndTypingSurvives() {
        store.lost = true
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it is SettingsUiState.Editing }
            model.edit((state.value as SettingsUiState.Editing).form.copy(syncIntervalMinutes = 480))
            model.save()
            state.awaiting { it is SettingsUiState.Editing && it.error != null }
        }

        assertEquals(SettingsFormError.NotSaved, (state as SettingsUiState.Editing).error)
        assertEquals(480L, state.form.syncIntervalMinutes)
        assertEquals(false, state.isSaved)
    }
}
