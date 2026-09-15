package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Лечение в строке списка (PLAN H3 №13): что записано — то и сказано, нехватка — числом и днём. */
class CoursePresentationMapperTest {

    @Test
    fun aDraftShowsOnlyWhatIsWrittenSoFar() {
        val row = course(title = "Нурофен", dose = dose("2")).projection().toPresentationDTO()

        assertEquals(CoursePresentationDTO.Kind.DRAFT, row.kind)
        assertEquals(QuantityPresentationDTO("2", TABLETS.toPresentationDTO()), row.dose)
        assertEquals(null, row.form)
        assertEquals(null, row.schedule)
        assertEquals(null, row.shortage)
    }

    /** Нехватка — сколько приёмов и с какого дня, день — в зоне курса. */
    @Test
    fun aShortageIsNamedInDosesAndByTheDayItStarts() {
        val record = courseRecord().projection()
        val coverage = CourseCoverage(
            requiredDoses = Doses(7),
            coveredDoses = Doses(3),
            coveredUntil = null,
            firstUncoveredAt = Instant.parse("2027-03-03T21:30:00Z"),
            zone = MOSCOW,
            perSource = emptyList()
        )

        val row = record.toPresentationDTO(coverage)

        assertEquals(CoursePresentationDTO.Kind.RUNNING, row.kind)
        assertEquals(ShortagePresentationDTO(missingDoses = 4, firstUncoveredOn = LocalDate.of(2027, 3, 4)), row.shortage)
    }

    @Test
    fun aFullyCoveredCourseHasNoShortage() {
        val coverage = CourseCoverage(Doses(7), Doses(7), coveredUntil = null, firstUncoveredAt = null, zone = MOSCOW, perSource = emptyList())

        assertEquals(null, courseRecord().projection().toPresentationDTO(coverage).shortage)
    }

    /** Закрытая запись говорит, чем и когда кончилась; нехватки у неё нет, даже если обеспечение прислали. */
    @Test
    fun aClosedRecordNamesItsOutcomeAndDay() {
        val closed = courseRecord(
            outcome = CourseRecord.Outcome.CANCELLED,
            closedAt = Instant.parse("2027-03-05T22:00:00Z")
        ).projection()
        val stale = CourseCoverage(Doses(7), Doses(0), null, null, MOSCOW, emptyList())

        val row = closed.toPresentationDTO(stale)

        assertEquals(CoursePresentationDTO.Kind.CANCELLED, row.kind)
        assertEquals(LocalDate.of(2027, 3, 6), row.closedOn)
        assertEquals(null, row.shortage)
    }
}
