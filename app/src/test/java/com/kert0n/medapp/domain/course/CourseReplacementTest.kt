package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.doses
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Изменившееся лечение — это **конец прежнего эпизода и новый**, а не правка действующего
 * (PLAN C1, D5). Сценарий проверяется целиком, потому что по частям он выглядит безобидно:
 * каждый отдельный отказ понятен, а вместе они и есть то самое решение — прошлые приёмы не
 * должны оказаться записанными в дозе, которой у лечения больше нет.
 */
class CourseReplacementTest {

    private val newCourseId: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000053")

    private val old = activeCourse(sources = listOf(source(PACK, 5)))

    private val oldRecord = courseRecord(startedAt = EARLIER)

    private val takenYesterday = plannedIntake(courseRevision = old.revision.number)
        .confirm(pack().take(dose("2"), EARLIER).getOrThrow())

    private val plannedTomorrow = plannedIntake(
        id = Uuid.parse("00000000-0000-4000-8000-000000000071"),
        courseRevision = old.revision.number
    )

    @Test
    fun replacementIsAnotherEpisodeAndTheOldOneStaysAsARecord() {
        val closed = oldRecord.close(CourseRecord.Outcome.CANCELLED, LATER)
        val replacement = course(
            id = newCourseId,
            title = oldRecord.title,
            dose = dose("3"),
            form = TABLET_FORM,
            totalDoses = 10,
            createdAt = LATER,
            updatedAt = LATER
        )
            .setSchedule(schedule(times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))), LATER)
            .attach(pack(id = PACK, form = TABLET_FORM, quantity = tablets("20")), 5.doses, LATER)
            .getOrThrow()
            .activate(LATER)
            .getOrThrow()

        // Другой эпизод, а не тот же самый: тождество — id.
        assertNotEquals(closed, replacement.record)
        assertTrue(replacement.record.isOpen)
        assertEquals(dose("3"), replacement.course.dose)

        // Прежний эпизод остался записью — вместе с назначением, которое исчезло с планом.
        assertEquals(CourseRecord.Outcome.CANCELLED, closed.outcome)
        assertEquals(LATER, closed.closedAt)
        assertEquals(EARLIER, closed.startedAt)
        assertEquals(schedule(), closed.prescription.schedule)
        assertEquals(dose("2"), closed.prescription.dose)
    }

    @Test
    fun closingDoesNotRewriteWhatAlreadyHappened() {
        // Конец лечения не переписывает состоявшиеся приёмы, их времена и количества (PLAN D5).
        assertEquals(IntakeStatus.TAKEN, takenYesterday.status)
        assertEquals(dose("2"), takenYesterday.taken?.amount)
        assertEquals(old.id, takenYesterday.courseId)
        assertEquals(old.revision, takenYesterday.courseRevision)

        // А будущий неотвеченный пункт отменяется вместе с лечением.
        val cancelledItem = plannedTomorrow.cancel(LATER)
        assertEquals(IntakeStatus.CANCELLED, cancelledItem.status)
        assertEquals(dose("2"), cancelledItem.plannedAmount)
        assertThrows(IllegalStateException::class.java) { takenYesterday.cancel(LATER) }
    }

    @Test
    fun intakeStillFindsItsEpisodeWhenThePlanIsGone() {
        // План уничтожается вместе с концом лечения, а ссылка приёма — тождество эпизода, и она
        // не повисает: «что принималось по этому поводу» отвечается ею и после закрытия.
        val closed = oldRecord.close(CourseRecord.Outcome.COMPLETED, LATER)
        assertEquals(closed.id, takenYesterday.courseId)
        assertEquals(closed.id, old.id)
    }

    @Test
    fun closedEpisodeIsNotClosedTwice() {
        val closed = oldRecord.close(CourseRecord.Outcome.CANCELLED, LATER)
        assertThrows(IllegalStateException::class.java) {
            closed.close(CourseRecord.Outcome.COMPLETED, LATER)
        }
    }

    @Test
    fun treatmentDoesNotEndBeforeItStarts() {
        assertThrows(IllegalArgumentException::class.java) {
            courseRecord(startedAt = LATER).close(CourseRecord.Outcome.COMPLETED, EARLIER)
        }
    }
}
