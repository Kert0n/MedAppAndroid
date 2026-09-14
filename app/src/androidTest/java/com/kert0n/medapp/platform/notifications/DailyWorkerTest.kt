package com.kert0n.medapp.platform.notifications

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Проход дня планировщиком системы: воркер зовёт тот же сценарий, что вход в приложение (PLAN D8). */
@RunWith(AndroidJUnit4::class)
class DailyWorkerTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MedAppDatabase
    private lateinit var work: WorkManager
    private val now: Instant = Instant.parse("2027-03-11T05:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        work = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = database.close()

    private fun worker(scenarios: Scenarios): DailyWorker =
        TestListenableWorkerBuilder<DailyWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    DailyWorker(appContext, workerParameters, scenarios.dailyRound)
            })
            .build()

    @Test
    fun theWorkerMarksYesterdayMissedAndArmsToday(): Unit = runBlocking {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val yesterday = Scenarios(database, Instant.parse("2027-03-10T05:00:00Z"))
        val created = yesterday.courseDrafting.create("Ибупрофен")
        val draft = (yesterday.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")), CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)), CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        yesterday.courseActivation.activate(draft.id, draft.revision)
        val today = Scenarios(database, now)

        val result = worker(today).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val statuses = database.intakeRepository().ofCourse(draft.id).filterIsInstance<CourseIntake>().associate { it.slot.localDate to it.status }
        assertEquals(IntakeStatus.MISSED, statuses[LocalDate.of(2027, 3, 10)])
        assertEquals(IntakeStatus.PLANNED, statuses[LocalDate.of(2027, 3, 11)])
        assertEquals(2, today.reminders.scheduled.size)
        assertTrue(today.notifier.shown.any { it.kind == com.kert0n.medapp.domain.notification.NotificationKind.INTAKE_MISSED })
    }

    /** Ежедневная задача одна: повторная постановка её не сдвигает; «сейчас» — отдельная разовая. */
    @Test
    fun theDailyWorkIsOneAndKept() {
        val schedule = WorkManagerDailySchedule({ work }, clock)

        schedule.keepDaily(LocalTime.of(9, 0))
        schedule.keepDaily(LocalTime.of(9, 0))
        schedule.runNow()

        val daily = work.getWorkInfosForUniqueWork(WorkManagerDailySchedule.DAILY).get()
        assertEquals(1, daily.size)
        assertTrue(daily.single().state == WorkInfo.State.ENQUEUED || daily.single().state == WorkInfo.State.RUNNING)
        assertEquals(1, work.getWorkInfosForUniqueWork(WorkManagerDailySchedule.NOW).get().size)
    }
}
