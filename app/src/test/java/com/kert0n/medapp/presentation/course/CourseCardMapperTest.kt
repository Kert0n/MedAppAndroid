package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Карточка лечения читает то, что записано (PLAN H3 №14): пункт несёт свою плановую дозу, а дни
 * считаются в зоне курса — у полуночи день устройства бывает уже другим (D5).
 */
class CourseCardMapperTest {

    private val zone = MOSCOW

    private fun scheduled(
        status: IntakeStatus = IntakeStatus.PLANNED,
        taken: TakenDose? = null
    ) = IntakeProjection.Scheduled(
        id = Uuid.random(),
        courseId = Uuid.random(),
        courseRevision = Revision(1),
        slot = ScheduledOccurrence(
            LocalDate.of(2027, 3, 10),
            LocalTime.of(9, 0),
            Instant.parse("2027-03-10T06:00:00Z")
        ),
        plannedAmount = dose("2"),
        plannedPackage = pack(id = PACK, name = "Нурофен").ref,
        answer = null,
        status = status,
        taken = taken
    )

    /** Пункт: день и время назначения, плановая доза и коробка, из которой он берётся. */
    @Test
    fun aScheduledItemCarriesItsOwnPlannedDose() {
        val item = scheduled().toPresentationDTO(zone)

        assertEquals(LocalDate.of(2027, 3, 10), item.on)
        assertEquals(LocalTime.of(9, 0), item.at)
        assertEquals("2", item.amount.amount)
        assertEquals("Нурофен", item.packageName)
        assertEquals(IntakeStatus.PLANNED, item.status)
        assertNull(item.takenAt)
    }

    /** У подтверждённого есть своё время и своё количество: принято не всегда ровно плановое. */
    @Test
    fun aTakenItemTellsWhenAndHowMuchWasActuallyTaken() {
        val taken = TakenDose(
            pkg = pack(id = PACK, name = "Нурофен").ref,
            amount = dose("1"),
            at = Instant.parse("2027-03-10T06:12:00Z")
        )

        val item = scheduled(IntakeStatus.TAKEN, taken).toPresentationDTO(zone)

        // 06:12 UTC — это 09:12 в Москве: время курса, а не устройства.
        assertEquals(LocalTime.of(9, 12), item.takenAt)
        assertEquals("1", item.takenAmount?.amount)
        assertEquals("2", item.amount.amount)
    }

    /** Обеспечение словами экрана: числа приёмов и день, с которого не хватает. */
    @Test
    fun coverageTellsWhatIsNeededAndFromWhichDayItIsMissing() {
        val coverage = CourseCoverage(
            requiredDoses = Doses(28),
            coveredDoses = Doses(9),
            coveredUntil = Instant.parse("2027-03-10T06:00:00Z"),
            firstUncoveredAt = Instant.parse("2027-03-11T21:30:00Z"),
            zone = zone,
            perSource = emptyList()
        )

        val shown = coverage.toPresentationDTO()

        assertEquals(28, shown.requiredDoses)
        assertEquals(9, shown.coveredDoses)
        assertEquals(19, shown.missingDoses)
        // 21:30 UTC — уже двенадцатое в Москве: день считается в зоне курса.
        assertEquals(LocalDate.of(2027, 3, 12), shown.firstUncoveredOn)
        assertEquals(LocalDate.of(2027, 3, 10), shown.coveredUntilOn)
    }

    /** Обеспеченный целиком курс нехватки не показывает. */
    @Test
    fun fullCoverageHasNothingMissing() {
        val coverage = CourseCoverage(
            requiredDoses = Doses(4),
            coveredDoses = Doses(4),
            coveredUntil = Instant.parse("2027-03-13T06:00:00Z"),
            firstUncoveredAt = null,
            zone = zone,
            perSource = emptyList()
        )

        val shown = coverage.toPresentationDTO()

        assertTrue(shown.isFullyCovered)
        assertNull(shown.firstUncoveredOn)
    }

    /** Сокращение: было столько, стало столько — и из-за какой коробки. */
    @Test
    fun aReductionNamesTheBoxThatCausedIt() {
        val reduction = CoverageReduction(
            id = Uuid.random(),
            courseId = Uuid.random(),
            packageId = PACK,
            coveredBefore = 12.doses,
            coveredAfter = 7.doses,
            at = Instant.parse("2027-03-15T10:00:00Z")
        )

        val shown = reduction.toPresentationDTO(zone, mapOf(PACK to "Нурофен, 20 капс."))

        assertEquals(LocalDate.of(2027, 3, 15), shown.on)
        assertEquals("Нурофен, 20 капс.", shown.packageName)
        assertEquals(12, shown.coveredBefore)
        assertEquals(7, shown.coveredAfter)
    }

    /** Коробки уже нет в приёмах — сокращение всё равно читается: имя просто неизвестно. */
    @Test
    fun aReductionWithoutAKnownBoxStillReads() {
        val reduction = CoverageReduction(
            id = Uuid.random(),
            courseId = Uuid.random(),
            packageId = PACK,
            coveredBefore = 3.doses,
            coveredAfter = 0.doses,
            at = Instant.parse("2027-03-15T10:00:00Z")
        )

        val shown = reduction.toPresentationDTO(zone, emptyMap())

        assertNull(shown.packageName)
        assertEquals(0, shown.coveredAfter)
    }
}
