package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.FakeSettings
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.notification.ReminderRoomRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * О сокращении обеспечения сообщают, называя дату (PLAN D8): событие — сразу и один раз; за три дня
 * до первого необеспеченного пункта и в его день — по календарю зоны курса.
 */
@RunWith(AndroidJUnit4::class)
class CoverageNoticeTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private lateinit var planning: NotificationPlanning
    private val settings = FakeSettings()
    private val now: Instant = Instant.parse("2027-03-10T06:00:00Z")
    private val today = LocalDate.of(2027, 3, 10)

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        planning = NotificationPlanning(database.intakeRepository(), database.packageRepository(), database.courseRepository(), settings)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    /** По две таблетки раз в день с сегодня, 10 доз, выделено 10 из 20 — обеспечен полностью. */
    private suspend fun treated(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = today)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(10))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private fun kinds(due: List<Reminder>) = due.map { it.kind }

    /** Пересчёт до 8 таблеток: 4 дозы из 10 — событие сразу, «за три дня» на седьмой день, «в день» — на восьмой. */
    @Test
    fun aReductionIsAnnouncedOnceAndTheGapIsAnnouncedByItsDate() = runTest {
        val id = treated()
        assertEquals(emptyList<NotificationKind>(), kinds(planning.coverageDue(now)))

        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("8")))

        assertEquals(listOf(NotificationKind.COVERAGE_SHORT), kinds(planning.coverageDue(now)))
        // Первый необеспеченный — пятый пункт, 14 марта: за три дня — 11-го, в день — 14-го.
        assertEquals(listOf(NotificationKind.COVERAGE_SHORT, NotificationKind.COVERAGE_3D), kinds(planning.coverageDue(now.plus(Duration.ofDays(1)))))
        assertEquals(listOf(NotificationKind.COVERAGE_SHORT), kinds(planning.coverageDue(now.plus(Duration.ofDays(2)))))
        assertEquals(listOf(NotificationKind.COVERAGE_SHORT, NotificationKind.COVERAGE_END), kinds(planning.coverageDue(now.plus(Duration.ofDays(4)))))

        // Событие говорится один раз: повторная сверка заводит обязательство, которого ещё нет,
        // а сказанное не трогает.
        scenarios.reminderStore.raiseAll(planning.coverageDue(now))
        scenarios.reminderOutbox.pass()
        scenarios.reminderStore.raiseAll(planning.coverageDue(now))
        scenarios.reminderOutbox.pass()
        val short = scenarios.notifier.shown.filter { it.kind == NotificationKind.COVERAGE_SHORT }
        assertEquals(1, short.size)
        assertEquals(id, (short.single().target as com.kert0n.medapp.domain.notification.NotificationTarget.CourseSources).courseId)
    }

    /** Порог — из настроек: два дня вместо трёх. */
    @Test
    fun theThresholdComesFromTheSettings() = runTest {
        treated()
        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("8")))
        settings.settings = com.kert0n.medapp.domain.notification.NotificationSettings(coverageThresholdDays = 2)

        assertEquals(listOf(NotificationKind.COVERAGE_SHORT, NotificationKind.COVERAGE_3D), kinds(planning.coverageDue(now.plus(Duration.ofDays(2)))))
        assertEquals(listOf(NotificationKind.COVERAGE_SHORT), kinds(planning.coverageDue(now.plus(Duration.ofDays(1)))))
    }
}
