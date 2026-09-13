package com.kert0n.medapp.storage.course

import android.database.sqlite.SQLiteConstraintException
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.rejectedByDatabase
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity as toPackageStorageEntity
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.tablets

/**
 * Курс и его источники хранят порядок, а времена не заводятся дважды. Черновик и живой план —
 * одна таблица, и различает их наличие записи эпизода (PLAN F1, F2, D5).
 */
class CourseDaoTest {

    private lateinit var database: MedAppDatabase
    private val courses get() = database.courses()

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        for (id in listOf(PACK, OTHER_PACK)) {
            val pkg = pack(id = id)
            database.packages().save(pkg)
        }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun draftComesBackAsADraft() = runTest {
        val draft = course(note = "спросить у врача")
        courses.saveCourse(draft.toStorageEntity(), emptyList(), emptyList())

        val row = requireNotNull(courses.findPlan(COURSE))
        assertTrue(row.isDraft)
        assertEquals("спросить у врача", row.toDraft(VOCABULARY).note)
    }

    @Test
    fun sourcesKeepTheOrderOfSpending() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 4)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )

        val restored = requireNotNull(courses.findPlan(COURSE)).toPlan(VOCABULARY)
        assertEquals(listOf(PACK, OTHER_PACK), restored.sources.map { it.pkg.id })
        assertEquals(plan.sources, restored.sources)
    }

    /**
     * Пачку выбросили: курс теряет её как источник, но сам остаётся — лечение назначено человеку,
     * а не коробке (PLAN D5, D3). Снимает источник доменный переход, и редакция уходит вперёд:
     * тот, кто читал курс до этого, узнает, что состав уже другой.
     */
    @Test
    fun aReleasedSourceLeavesTheCourseAndMovesItsRevision() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 4)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )

        courses.releaseSource(pack(id = PACK).ref, VOCABULARY, LATER)
        assertEquals(1, database.packages().delete(PACK))

        val left = requireNotNull(courses.findPlan(COURSE)).toPlan(VOCABULARY)
        assertEquals(listOf(OTHER_PACK), left.sources.map { it.pkg.id })
        assertEquals(plan.revision.next(), left.revision)
    }

    /** Уникальность позиции ловит сбой перетаскивания: два источника на одном месте невозможны. */
    @Test
    fun twoSourcesCannotShareOnePosition() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )

        val refusal = rejectedByDatabase {
            courses.insertSources(
                listOf(CourseSourceStorageEntity(COURSE, OTHER_PACK, position = 0, allocatedDoses = 4))
            )
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    /** Одно и то же время дважды — один приём, а не два: ключ из пары этого не допускает. */
    @Test
    fun oneTimeCannotBeStoredTwice() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 1)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )

        val refusal = rejectedByDatabase {
            courses.insertTimes(listOf(CourseTimeStorageEntity(COURSE, LocalTime.of(9, 0))))
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    @Test
    fun savingAgainReplacesTimesAndSourcesInsteadOfAddingToThem() = runTest {
        val first = activeCourse(
            schedule = schedule(times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))),
            sources = listOf(source(PACK, 5), source(OTHER_PACK, 4))
        )
        courses.saveCourse(
            first.toStorageEntity(),
            first.schedule.toTimeStorageEntities(COURSE),
            first.medicine.toSourceStorageEntities(COURSE)
        )

        val second = activeCourse(
            schedule = schedule(times = listOf(LocalTime.of(12, 0))),
            sources = listOf(source(OTHER_PACK, 7))
        )
        courses.saveCourse(
            second.toStorageEntity(),
            second.schedule.toTimeStorageEntities(COURSE),
            second.medicine.toSourceStorageEntities(COURSE)
        )

        val restored = requireNotNull(courses.findPlan(COURSE)).toPlan(VOCABULARY)
        assertEquals(listOf(LocalTime.of(12, 0)), restored.schedule.times)
        assertEquals(listOf(OTHER_PACK), restored.sources.map { it.pkg.id })
    }

    /**
     * Запись эпизода переживает план: строка `courses` исчезает, а времена назначения остаются
     * на месте — они лежат по тождеству эпизода, а не по плану (PLAN D5).
     */
    @Test
    fun recordOutlivesThePlanTogetherWithItsScheduleTimes() = runTest {
        val plan = activeCourse(
            schedule = schedule(times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))),
            sources = listOf(source(PACK, 5))
        )
        val record = courseRecord(prescription = plan.prescription)
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )
        courses.upsertRecord(record.toStorageEntity())

        courses.deleteSourcesOf(COURSE)
        courses.deletePlan(COURSE)

        assertNull(courses.findPlan(COURSE))
        val restored = requireNotNull(courses.findRecord(COURSE)).toDomain(VOCABULARY)
        assertEquals(record.prescription, restored.prescription)
        assertEquals(plan.schedule.times, restored.prescription.schedule.times)
    }

    /**
     * Изменённое назначение ложится в план и в снимок записи одной транзакцией: назначение лежит в
     * двух строках, и разойтись им нельзя (PLAN F5). Запись условна по редакции.
     */
    @Test
    fun anAmendedPrescriptionGoesIntoThePlanAndTheRecordTogether() = runTest {
        val plan = activeCourse()
        courses.saveCourse(plan.toStorageEntity(), plan.schedule.toTimeStorageEntities(COURSE), emptyList())
        courses.upsertRecord(courseRecord(prescription = plan.prescription).toStorageEntity())
        val shortened = plan.setTotalDoses(3.doses, LATER)
        val repository = database.courseRepository()

        assertTrue(repository.amend(shortened, expected = plan.revision))
        assertEquals(3.doses, requireNotNull(repository.findPlan(COURSE)).totalDoses)
        assertEquals(shortened.revision, requireNotNull(repository.findPlan(COURSE)).revision)
        assertEquals(3.doses, requireNotNull(repository.findRecord(COURSE)).prescription.totalDoses)

        // Правка из устаревшей редакции не ложится ни в план, ни в запись.
        assertEquals(false, repository.amend(shortened.setTotalDoses(5.doses, LATER), expected = plan.revision))
        assertEquals(3.doses, requireNotNull(repository.findRecord(COURSE)).prescription.totalDoses)
    }

    /** Доза мимо плана ложится вместе с пересчитанными выделениями, условно по редакции. */
    @Test
    fun dosesTakenOffPlanAreWrittenWithTheReallocation() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )
        val corrected = plan.setTakenOffPlan(2.doses, availability(PACK to tablets("20")), LATER)

        assertTrue(
            courses.updateAllocations(
                corrected.toStorageEntity(),
                corrected.medicine.toSourceStorageEntities(COURSE),
                expected = plan.revision
            )
        )
        val restored = requireNotNull(courses.findPlan(COURSE)).toPlan(VOCABULARY)
        assertEquals(2.doses, restored.takenOffPlan)
        assertEquals(listOf(3.doses), restored.sources.map { it.allocatedDoses })
        assertEquals(corrected.revision, restored.revision)
    }

    /**
     * Состав курса не меняется мимо самого курса: пока пачка в источниках, строки её не убрать.
     * Каскад дал бы верный набор строк при прежней редакции — курс не узнал бы, что изменился
     * (PLAN D5, F2).
     */
    @Test
    fun aPackageHeldAsASourceIsNotRemovedSilently() = runTest {
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        courses.saveCourse(
            plan.toStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )

        val refusal = runCatching { database.packages().delete(PACK) }.exceptionOrNull()

        assertTrue("$refusal", refusal is SQLiteConstraintException)
        assertEquals(listOf(PACK), courses.sourcePackagesOf(COURSE))
    }
}
