package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseAmendment
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.prescription
import com.kert0n.medapp.fixture.schedule
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Изменение идущего лечения несёт **только тронутое** (PLAN D5, C1 «Изменение лечения»).
 *
 * Проверяется здесь, а не на сценарии: переходы домена идемпотентны — то же расписание он
 * принимает и ничего не меняет, — и по базе «послали всё» неотличимо от «послали одно». Отличается
 * это в том, что экран **просит**, и просьба проверяется там, где её собирают.
 */
class CourseChangesTest {

    private val prescribed = prescription()

    private fun described(
        dose: com.kert0n.medapp.domain.value.Dose? = prescribed.dose,
        form: com.kert0n.medapp.domain.value.DosageForm? = prescribed.form,
        schedule: com.kert0n.medapp.domain.course.CourseSchedule? = prescribed.schedule,
        totalDoses: Doses? = prescribed.totalDoses
    ) = CourseDescription("Нурофен", null, dose, form, schedule, totalDoses)

    /** Ничего не тронуто — сценарию не о чем говорить. */
    @Test
    fun anUntouchedPrescriptionAsksForNothing() {
        assertTrue(described().changesSince(prescribed).isEmpty())
    }

    /** Сменили дозу — уходит одна доза, а не всё назначение целиком. */
    @Test
    fun changingTheDoseAsksOnlyForTheDose() {
        val changes = described(dose = dose("3")).changesSince(prescribed)

        assertEquals(listOf(CourseAmendment.Change.SetDose(dose("3"))), changes)
    }

    /** Сменили форму — уходит форма; расписание и число приёмов остаются нетронутыми. */
    @Test
    fun changingTheFormAsksOnlyForTheForm() {
        val changes = described(form = CAPSULE_FORM).changesSince(prescribed)

        assertEquals(listOf(CourseAmendment.Change.SetForm(CAPSULE_FORM)), changes)
    }

    /** Тронули расписание и число приёмов — уходят оба, и больше ничего. */
    @Test
    fun changingTheScheduleAndTheCountAsksForBoth() {
        val other = schedule(daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))

        val changes = described(schedule = other, totalDoses = Doses(12)).changesSince(prescribed)

        assertEquals(
            listOf(
                CourseAmendment.Change.SetSchedule(other),
                CourseAmendment.Change.SetTotalDoses(Doses(12))
            ),
            changes
        )
    }
}
