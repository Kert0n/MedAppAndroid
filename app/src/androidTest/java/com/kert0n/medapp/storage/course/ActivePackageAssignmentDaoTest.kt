package com.kert0n.medapp.storage.course

import android.database.sqlite.SQLiteConstraintException
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.rejectedByDatabase
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity as toPackageStorageEntity
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Одна упаковка не попадает в два активных курса. Держит это первичный ключ по пачке, а не
 * проверка «а нет ли уже» перед вставкой: два экрана прочитали бы пусто одновременно и оба
 * записали бы себя (PLAN F1, F2).
 */
class ActivePackageAssignmentDaoTest {

    private val secondCourse: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000052")

    private lateinit var database: MedAppDatabase
    private val courses get() = database.courses()

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        for (id in listOf(PACK, OTHER_PACK)) {
            val pkg = pack(id = id)
            database.packages().save(pkg)
        }
        for (id in listOf(COURSE, secondCourse)) {
            val plan = activeCourse(id = id, sources = listOf(source(PACK, 1)))
            courses.upsertCourse(plan.toStorageEntity())
        }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun assignedPackageNamesItsCourse() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))
        assertEquals(COURSE, courses.courseHolding(PACK))
        assertNull(courses.courseHolding(OTHER_PACK))
    }

    @Test
    fun secondCourseCannotTakeAnOccupiedPackage() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))

        val refusal = rejectedByDatabase {
            courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, secondCourse))
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
        assertEquals(COURSE, courses.courseHolding(PACK))
    }

    /**
     * Две настоящие корутины на разных потоках, отпущенные одним сигналом: проверка перед
     * вставкой здесь и провалилась бы, потому что обе увидели бы пусто.
     */
    @Test
    fun simultaneousAssignmentFromTwoCoroutinesLeavesOneWinner() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val attempts = listOf(COURSE, secondCourse).map { courseId ->
            async(Dispatchers.IO) {
                start.await()
                runCatching {
                    courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, courseId))
                }
            }
        }
        start.complete(Unit)
        val outcomes = attempts.awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        val refusal = outcomes.first { it.isFailure }.exceptionOrNull()
        assertTrue("$refusal", refusal is SQLiteConstraintException)

        val holder = withContext(Dispatchers.IO) { courses.courseHolding(PACK) }
        assertTrue("$holder", holder == COURSE || holder == secondCourse)
    }

    @Test
    fun releasingByCourseFreesEveryPackageItHeld() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))
        courses.assignPackage(ActivePackageAssignmentStorageEntity(OTHER_PACK, COURSE))

        courses.releaseAssignmentsOf(COURSE)

        assertNull(courses.courseHolding(PACK))
        assertNull(courses.courseHolding(OTHER_PACK))
        assertEquals(emptyList<ActivePackageAssignmentStorageEntity>(), courses.assignmentsOf(COURSE))
    }

    @Test
    fun releasedPackageCanJoinAnotherCourse() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))
        courses.releasePackage(PACK)
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, secondCourse))
        assertEquals(secondCourse, courses.courseHolding(PACK))
    }

    /** Занятый план не исчезает в обход транзакции: назначение держит его ключом. */
    @Test
    fun aHeldPlanDoesNotDisappearBehindTheTransaction() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))

        val refusal = rejectedByDatabase { courses.deletePlan(COURSE) }

        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    /**
     * Занятость пачки снимает курс, а не схема: пока назначение есть, строки пачки не убрать.
     * Каскад освободил бы её молча — мимо редакции курса, которая занятость и охраняет
     * (PLAN D3, D5, F2).
     */
    @Test
    fun aPackageAssignedToACourseIsNotRemovedSilently() = runTest {
        courses.assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))

        val refusal = runCatching { database.packages().delete(PACK) }.exceptionOrNull()

        assertTrue("$refusal", refusal is SQLiteConstraintException)
        assertEquals(COURSE, courses.courseHolding(PACK))
    }
}
