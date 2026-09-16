package com.kert0n.medapp.presentation.plan

import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.IntakeAnswer
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.unplannedIntake
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * План дня — в строки экрана (PLAN H3 №12). Что попало в план, решает чтение; здесь — **чем строка
 * стала**: где она стоит, как названа и что о ней сказано.
 */
class DayPlanPresentationMapperTest {

    private val date: LocalDate = LocalDate.of(2027, 3, 10)

    /** Девять утра по Москве — шесть по Гринвичу: разница видна, и на ней проверка и стоит. */
    private val nineInTheMorning: Instant = Instant.parse("2027-03-10T06:00:00Z")

    private fun scheduled(
        answer: IntakeAnswer? = null,
        status: IntakeStatus = IntakeStatus.PLANNED,
        taken: TakenDose? = null
    ) = DayPlan.Item.Scheduled(
        intake = IntakeProjection.Scheduled(
            id = INTAKE,
            courseId = COURSE,
            courseRevision = Revision(1),
            slot = ScheduledOccurrence(date, LocalTime.of(9, 0), nineInTheMorning),
            plannedAmount = dose("2"),
            plannedPackage = pack(id = PACK).ref,
            answer = answer,
            status = status,
            taken = taken
        ),
        title = "Нурофен"
    )

    private fun page(vararg items: DayPlan.Item) =
        DayPlan(date, items.toList()).toPresentationDTO(daysAhead = 0, zone = MOSCOW)

    /**
     * Время строки — время **в зоне человека**: считай его по Гринвичу, и утренний приём встанет
     * в списке ночью, а день человека разойдётся с днём, который ему показали.
     */
    @Test
    fun theTimeOfARowIsToldInThePersonsZone() {
        val row = page(scheduled()).items.single()

        assertEquals(LocalTime.of(9, 0), row.at)
    }

    /**
     * У состоявшегося приёма строка говорит о том, что было, а не что назначали: взяли другую
     * коробку и другое количество — их человек и читает. Иначе история переписывается планом, и
     * «принято 2 из Нурофена» стоит там, где человек выпил одну из Ибупрофена.
     */
    @Test
    fun anAnsweredRowTellsWhatWasTakenNotWhatWasPlanned() {
        val fact = TakenDose(pack(id = OTHER_PACK, name = "Ибупрофен").ref, dose("1"), nineInTheMorning)

        val row = page(
            scheduled(answer = IntakeAnswer.Taken(fact), status = IntakeStatus.TAKEN, taken = fact)
        ).items.single()

        assertEquals("1", row.dose.amount)
        assertEquals("Ибупрофен", row.packageName)
        assertEquals(LocalTime.of(9, 0), row.answeredAt)
    }

    /**
     * Отвечают на записанные пункты; разовый приём и доза за окном календаря стоят отдельно и
     * ниже. Смешай их — и человек станет искать «Принял» у того, что уже случилось или чего ещё
     * не записано.
     */
    @Test
    fun oneOffsAndDosesBeyondTheWindowStandApart() {
        val expected = DayPlan.Item.Expected(
            courseId = COURSE,
            title = "Цетрин",
            slot = ScheduledOccurrence(date, LocalTime.of(21, 0), nineInTheMorning.plusSeconds(43_200)),
            dose = dose("1")
        )

        val day = page(scheduled(), expected, DayPlan.Item.OneOff(unplannedIntake(takenAt = nineInTheMorning).projection()))

        assertEquals(listOf("Нурофен"), day.items.map { it.title })
        assertEquals(
            listOf(DayItemPresentationDTO.State.EXPECTED, DayItemPresentationDTO.State.ONE_OFF),
            day.alsoOnThisDay.map { it.state }
        )
    }

    /**
     * Разовый приём называет **коробка**: лечения за ним нет, и подставить туда чужое название
     * значило бы приписать человеку курс, которого он не заводил (PLAN D6).
     */
    @Test
    fun aOneOffIsNamedByTheBoxItCameFrom() {
        val row = page(
            DayPlan.Item.OneOff(unplannedIntake(taken = pack(id = PACK, name = "Нурофен"), takenAt = nineInTheMorning).projection())
        ).alsoOnThisDay.single()

        assertEquals("Нурофен", row.title)
        assertEquals(null, row.courseId)
        assertEquals(DayItemPresentationDTO.State.ONE_OFF, row.state)
    }
}
