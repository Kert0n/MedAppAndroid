package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Список курсов (PLAN H3 №13): три полки из трёх чтений, нехватка — у идущего. */
class CourseListViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val courses = FakeCourseStorage()

    private fun listed(): CourseListPresentationDTO {
        val model = CourseListViewModel(courses)
        val state = watching(model.state) { it.awaiting { s -> s is ScreenState.Ready } }
        return (state as ScreenState.Ready).value
    }

    @Test
    fun runningDraftsAndFinishedAreThreeShelves() {
        val running = Uuid.parse("00000000-0000-4000-8000-000000000051")
        val draft = Uuid.parse("00000000-0000-4000-8000-000000000052")
        val finished = Uuid.parse("00000000-0000-4000-8000-000000000053")
        courses.holding(activeCourse(id = running), courseRecord(id = running, title = "Идёт"))
        courses.holding(course(id = draft, title = "Черновик"))
        courses.holding(
            courseRecord(id = finished, title = "Кончился", outcome = CourseRecord.Outcome.COMPLETED, closedAt = Instant.parse("2027-03-10T09:00:00Z"))
        )

        val list = listed()

        assertEquals(listOf("Идёт"), list.running.map { it.title })
        assertEquals(listOf("Черновик"), list.drafts.map { it.title })
        assertEquals(listOf("Кончился"), list.finished.map { it.title })
    }

    /** Нехватка приходит тем же чтением, что список: значок стоит у того курса, которому не хватает. */
    @Test
    fun theShortageStandsAtTheCourseThatLacks() {
        val short = Uuid.parse("00000000-0000-4000-8000-000000000051")
        val fine = Uuid.parse("00000000-0000-4000-8000-000000000052")
        courses.holding(activeCourse(id = short), courseRecord(id = short, title = "Не хватает"))
        courses.holding(activeCourse(id = fine), courseRecord(id = fine, title = "Хватает"))
        courses.covering(short, CourseCoverage(Doses(7), Doses(2), null, Instant.parse("2027-03-03T06:00:00Z"), MOSCOW, emptyList()))
        courses.covering(fine, CourseCoverage(Doses(7), Doses(7), null, null, MOSCOW, emptyList()))

        val list = listed()

        assertEquals(5, list.running.single { it.title == "Не хватает" }.shortage?.missingDoses)
        assertEquals(null, list.running.single { it.title == "Хватает" }.shortage)
    }

    /** Законченные — последнее сверху: то, что кончилось только что, ищут чаще. */
    @Test
    fun finishedCoursesGoLatestFirst() {
        val older = Uuid.parse("00000000-0000-4000-8000-000000000051")
        val newer = Uuid.parse("00000000-0000-4000-8000-000000000052")
        courses.holding(courseRecord(id = older, title = "Старое", outcome = CourseRecord.Outcome.CANCELLED, closedAt = Instant.parse("2027-03-01T09:00:00Z")))
        courses.holding(courseRecord(id = newer, title = "Новое", outcome = CourseRecord.Outcome.COMPLETED, closedAt = Instant.parse("2027-03-09T09:00:00Z")))

        assertEquals(listOf("Новое", "Старое"), listed().finished.map { it.title })
    }

    @Test
    fun anEmptyStoreIsAnEmptyListNotLoading() {
        assertEquals(true, listed().isEmpty)
    }
}
