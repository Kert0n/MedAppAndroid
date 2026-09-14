package com.kert0n.medapp.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.FakeDailySchedule
import com.kert0n.medapp.fixture.FakeSettingsStore
import com.kert0n.medapp.fixture.FakeSyncSchedule
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.SyncInterval
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Изменение настроек записывается и применяется сразу (PLAN D8, E4): выключенные напоминания
 * снимают обещанное тем же вызовом, а не следующим проходом дня; интервал уходит планировщику,
 * время сводки — ежедневному проходу; что не изменилось — не трогается; что не легло — не применяется.
 */
@RunWith(AndroidJUnit4::class)
class SettingsChangingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T05:00:00Z")
    private val store = FakeSettingsStore()
    private val sync = FakeSyncSchedule()
    private val daily = FakeDailySchedule()
    private lateinit var changing: SettingsChanging

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        changing = SettingsChanging(store, sync, daily, scenarios.notificationReconciliation, Clock.fixed(now, ZoneOffset.UTC))
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    /** Идущее лечение с плановыми пунктами: календарь обещает напомнить о каждом. */
    private suspend fun treated() {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10), times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(10))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
    }

    @Test
    fun changedSettingsAreSavedAndAppliedAtOnce() = runTest {
        treated()
        assertTrue(scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).isNotEmpty())
        val chosen = AppSettings(notifications = NotificationSettings(intakeRemindersEnabled = false))
        scenarios.notificationSettings.settings = chosen.notifications

        assertEquals(SettingsChanging.Outcome.SAVED, changing.change(chosen))

        assertEquals(chosen, store.saved)
        // Снятое гасит и прибирает владелец доставки после коммита; здесь важен переход.
        val states = scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { it.state }.toSet()
        assertEquals("выключенные напоминания не сняты тем же вызовом", setOf(Reminder.State.WITHDRAWN), states)
    }

    @Test
    fun whatDidNotLandIsNotApplied() = runTest {
        treated()
        store.lost = true
        val chosen = AppSettings(notifications = NotificationSettings(intakeRemindersEnabled = false))
        scenarios.notificationSettings.settings = chosen.notifications

        assertEquals(SettingsChanging.Outcome.NOT_SAVED, changing.change(chosen))

        assertEquals(AppSettings.DEFAULT, store.saved)
        val states = scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).map { it.state }.toSet()
        assertEquals("сверка позвана, хотя настройки не легли", setOf(Reminder.State.DUE), states)
    }

    /** Только интервал: он уходит планировщику; сверка не нужна, обещанное и проход дня не трогаются. */
    @Test
    fun changingOnlyTheIntervalGoesToTheSchedulerAlone() = runTest {
        treated()
        val owed = scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).size
        scenarios.notificationSettings.settings = NotificationSettings(intakeRemindersEnabled = false)
        val interval = SyncInterval(Duration.ofHours(2))

        changing.change(AppSettings(syncInterval = interval))

        assertEquals(listOf(interval), sync.kept)
        assertEquals(emptyList<LocalTime>(), daily.kept)
        assertEquals(owed, scenarios.reminderStore.ofKinds(listOf(NotificationKind.INTAKE_DUE)).size)
    }

    /** Время сводки — ежедневному проходу; интервал при этом не трогается. */
    @Test
    fun aNewDigestTimeGoesToTheDailySchedule() = runTest {
        changing.change(AppSettings(notifications = NotificationSettings(digestAt = LocalTime.of(18, 0))))

        assertEquals(listOf(LocalTime.of(18, 0)), daily.kept)
        assertEquals(emptyList<SyncInterval>(), sync.kept)
    }

    /** Те же настройки — ничего не применяется: применять нечего. */
    @Test
    fun unchangedSettingsApplyNothing() = runTest {
        changing.change(AppSettings.DEFAULT)

        assertEquals(emptyList<SyncInterval>(), sync.kept)
        assertEquals(emptyList<LocalTime>(), daily.kept)
    }
}
