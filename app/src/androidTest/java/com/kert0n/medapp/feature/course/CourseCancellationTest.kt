package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Отмена лечения — закрытие эпизода исходом «отменено»: прошлое цело, будущее отменено, пачки свободны (PLAN D5). */
@RunWith(AndroidJUnit4::class)
class CourseCancellationTest {

    private lateinit var database: MedAppDatabase
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        database.packageRepository().add(
            pack(id = PACK, medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM)
        )
    }

    @After
    fun tearDown() = database.close()

    /** Лечение с 8 марта: к 10-му два дня пропущены, дальше пункты ждут. */
    private suspend fun started(): Uuid {
        val scenarios = Scenarios(database, now)
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 8))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()
        return draft.id
    }

    @Test
    fun aCancelledTreatmentKeepsItsPastAndFreesItsPackages() = runTest {
        val id = started()

        val outcome = Scenarios(database, now).courseCancellation.cancel(id)

        assertEquals(CourseCancellation.Outcome.CANCELLED, outcome)
        val courses = database.courseRepository()
        assertNull(courses.findPlan(id))
        assertEquals(CourseRecord.Outcome.CANCELLED, courses.findRecord(id)?.outcome)
        assertNull(courses.courseHolding(PACK))
        val statuses = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().map { it.status }.toSet()
        assertEquals(setOf(IntakeStatus.MISSED, IntakeStatus.CANCELLED), statuses)
        val commands = database.syncOperations().all().map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }
        assertEquals(PackageSyncCommand.ReleaseClaim(PACK), commands.last())
    }

    /**
     * Календарь давно не приводили в порядок, а лечение отменяют: неответ прошедших дней остаётся
     * пропуском, а не становится отменённым пунктом — отменённый пропуском уже не станет.
     */
    @Test
    fun overdueDosesAreMissedNotCancelled() = runTest {
        val id = started()
        val twoDaysLater = Instant.parse("2027-03-12T12:00:00Z")

        Scenarios(database, twoDaysLater).courseCancellation.cancel(id)

        val byDay = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().associate { it.slot.localDate to it.status }
        assertEquals(IntakeStatus.MISSED, byDay[LocalDate.of(2027, 3, 10)])
        assertEquals(IntakeStatus.MISSED, byDay[LocalDate.of(2027, 3, 11)])
        assertEquals(IntakeStatus.CANCELLED, byDay[LocalDate.of(2027, 3, 13)])
    }

    @Test
    fun aFinishedTreatmentIsNotCancelledAgain() = runTest {
        val id = started()
        val cancellation = Scenarios(database, now).courseCancellation

        cancellation.cancel(id)

        assertEquals(CourseCancellation.Outcome.ALREADY_FINISHED, cancellation.cancel(id))
        assertEquals(CourseCancellation.Outcome.GONE, cancellation.cancel(Uuid.random()))
        assertTrue(database.courseRepository().findRecord(id) != null)
    }
}
