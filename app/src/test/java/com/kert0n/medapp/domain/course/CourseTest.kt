package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.MILLILITRES
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import com.kert0n.medapp.fixture.MOSCOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import com.kert0n.medapp.fixture.prescribedDraft
import org.junit.Assert.assertTrue
import com.kert0n.medapp.fixture.prescription

/**
 * Курс начинается заметкой: черновик с одним названием — законное сохранённое состояние, а не
 * полуфабрикат (PLAN D5).
 */
class CourseTest {

    @Test
    fun draftWithNothingButATitleIsALegitimateCourse() {
        val draft = course()
        assertNull(draft.note)
        assertNull(draft.dose)
        assertNull(draft.form)
        assertNull(draft.totalDoses)
    }

    @Test
    fun doseIsAValueWithItsUnitFromTheVocabularyNotFromAPack() {
        // Единицу человек выбирает из словаря вместе с числом: пачки для этого не нужно.
        val dosed = course(dose = dose("2"))
        assertEquals(Dose(Quantity(BigDecimal("2"), TABLETS)), dosed.dose)
        assertEquals(TABLETS, dosed.unit)
        assertTrue(dosed.medicine.isEmpty)
    }

    @Test
    fun renamedCourseIsTheSameCourse() {
        val original = course(title = "Парацетомол")
        val fixed = original.rename("Парацетамол", note = "по рецепту", at = LATER)
        assertEquals(original, fixed)
        assertEquals(original.hashCode(), fixed.hashCode())
        assertEquals("Парацетамол", fixed.title)
        assertEquals("по рецепту", fixed.note)
        assertEquals(LATER, fixed.updatedAt)
    }

    @Test
    fun renamingDoesNotAgeTheRevision() {
        // Редакция связывает курс с уже материализованными приёмами: исправленная опечатка не
        // должна объявлять их устаревшими.
        val renamed = course(revision = 3).rename("Другое название", note = null, at = LATER)
        assertEquals(Revision(3), renamed.revision)
    }

    @Test
    fun startedTreatmentStillGetsRenamed() {
        // Название и заметка — не назначенное лечение, и правятся они всегда (PLAN D5). Живёт имя
        // в записи эпизода: так называют лечение, а не расписание, и второго места для него нет.
        val record = courseRecord(title = "Курс")
        assertEquals("Курс от врача", record.rename("Курс от врача", null).title)
    }

    @Test
    fun settingTheDraftDoseRaisesTheRevision() {
        val dosed = course().setDose(dose("2"), at = LATER).getOrThrow()
        assertEquals(dose("2"), dosed.dose)
        assertEquals(Revision(1), dosed.revision)
        assertEquals(LATER, dosed.updatedAt)
    }

    @Test
    fun settingTheDraftFormAndTotalRaisesTheRevision() {
        val formed = course().setForm(TABLET_FORM, at = LATER).getOrThrow()
        assertEquals(TABLET_FORM, formed.form)
        assertEquals(Revision(1), formed.revision)
        val counted = formed.setTotalDoses(10.doses, at = LATER)
        assertEquals(10.doses, counted.totalDoses)
        assertEquals(Revision(2), counted.revision)
    }

    @Test
    fun settingTheDraftScheduleRaisesTheRevision() {
        // Расписание меняет состав будущих пунктов, поэтому редакция растёт — в отличие от
        // переименования.
        val planned = course().setSchedule(schedule(), at = LATER).getOrThrow()
        assertEquals(schedule(), planned.schedule)
        assertEquals(Revision(1), planned.revision)
    }

    @Test
    fun activationCarriesTheDoseAndScheduleOverUnchanged() {
        // Менять их после активации нечем: переходов `setDose` и `setSchedule` у назначенного
        // курса нет вовсе. Изменившееся лечение — отмена прежнего курса и новый (PLAN D5).
        val draft = prescribedDraft(schedule = schedule(), totalDoses = 7)
            .attach(pack(form = TABLET_FORM), 1.doses, LATER).getOrThrow()
        val started = draft.activate(LATER).getOrThrow()
        assertEquals(dose("2"), started.course.dose)
        assertEquals(TABLET_FORM, started.course.form)
        assertEquals(7.doses, started.course.totalDoses)
        assertEquals(schedule(), started.course.schedule)
        // Запись эпизода несёт то же назначение: расходиться им нечем — менять его нельзя.
        assertEquals(started.course.prescription, started.record.prescription)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDoseIsNotTreatment() {
        course(dose = dose("0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroTotalDosesIsNotTreatmentEither() {
        // Правило живёт на назначении: собрать его с нулём доз нельзя ни одним путём.
        prescription(totalDoses = 0)
    }

    @Test
    fun draftWithZeroTotalDosesIsNotActivated() {
        val zero = prescribedDraft(schedule = schedule(), totalDoses = 0)
        assertEquals(
            CourseRejected.Reason.TOTAL_DOSES_MISSING,
            (zero.activate(LATER).exceptionOrNull() as CourseRejected).reason
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun startedTreatmentWithAZeroDoseIsNotRepresentable() {
        // Проверка черновика закрывала один путь; правило живёт на самой дозе, поэтому прямая
        // сборка действующего курса — и восстановление сохранённого — тоже её соблюдают.
        activeCourse(doseAmount = BigDecimal.ZERO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDoseIsRejected() {
        course(dose = dose("-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTitleIsRejected() {
        course(title = "   ")
    }

    @Test
    fun titleAndNoteFillingTheirLimitsFit() {
        val long = course(
            title = "я".repeat(CourseRecord.TITLE_MAX_LENGTH),
            note = "я".repeat(CourseRecord.NOTE_MAX_LENGTH)
        )
        assertEquals(COURSE, long.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noteOverTheLimitIsRejected() {
        course(note = "я".repeat(CourseRecord.NOTE_MAX_LENGTH + 1))
    }

    /**
     * Состав препарата знает курс: пункт курса принимают из его пачки, любая другая — внеплановый
     * факт (PLAN D5). Спрашивают об этом курс, а не перебирают источники на стороне.
     */
    @Test
    fun courseTellsItsOwnSourcesFromStrangers() {
        val treatment = activeCourse(sources = listOf(source(PACK, 5)))

        assertTrue(treatment.isSource(pack(id = PACK).ref))
        assertFalse(treatment.isSource(pack(id = OTHER_PACK).ref))
    }

    /**
     * Изменение лечения — тот же курс с другим назначением (PLAN C1, D5): доза и форма меняются,
     * пока под ними нет пачек другой единицы и формы; редакция растёт.
     */
    @Test
    fun aTreatmentChangesItsDoseAndFormUnlessPackagesDisagree() {
        val bare = activeCourse()
        val held = activeCourse(sources = listOf(source(PACK, 3)))

        val halved = bare.changeDose(dose("1"), LATER).getOrThrow()
        assertEquals(dose("1"), halved.dose)
        assertEquals(bare.revision.next(), halved.revision)
        assertEquals(dose("1"), held.changeDose(dose("1"), LATER).getOrThrow().dose)
        assertEquals(
            CourseRejected.Reason.UNIT_MISMATCH,
            (held.changeDose(Dose(Quantity(BigDecimal.ONE, MILLILITRES)), LATER).exceptionOrNull() as CourseRejected).reason
        )
        assertEquals(
            CourseRejected.Reason.FORM_MISMATCH,
            (held.changeForm(CAPSULE_FORM, LATER).exceptionOrNull() as CourseRejected).reason
        )
        assertEquals(bare, bare.changeDose(bare.dose, LATER).getOrThrow().also { assertEquals(bare.revision, it.revision) })
    }

    /** Новое расписание не начинается раньше сегодняшнего дня своей зоны: прошлое уже случилось. */
    @Test
    fun aNewScheduleDoesNotStartInThePast() {
        val treatment = activeCourse()
        val at = Instant.parse("2027-03-10T12:00:00Z")

        val tomorrow = treatment.changeSchedule(schedule(start = LocalDate.of(2027, 3, 11)), at).getOrThrow()
        val yesterday = treatment.changeSchedule(schedule(start = LocalDate.of(2027, 3, 9)), at)

        assertEquals(LocalDate.of(2027, 3, 11), tomorrow.schedule.start)
        assertEquals(CourseRejected.Reason.SCHEDULE_IN_PAST, (yesterday.exceptionOrNull() as CourseRejected).reason)
    }

    /** Черновик тоже не начинают с прошедшего дня: правило одно и у лечения, и у его заготовки. */
    @Test
    fun aDraftScheduleDoesNotStartInThePastEither() {
        val at = Instant.parse("2027-03-10T12:00:00Z")

        val today = course().setSchedule(schedule(start = LocalDate.of(2027, 3, 10)), at).getOrThrow()
        val yesterday = course().setSchedule(schedule(start = LocalDate.of(2027, 3, 9)), at)

        assertEquals(LocalDate.of(2027, 3, 10), today.schedule?.start)
        assertEquals(CourseRejected.Reason.SCHEDULE_IN_PAST, (yesterday.exceptionOrNull() as CourseRejected).reason)
    }

    /**
     * Черновик, заполненный в понедельник датой «сегодня», в среду с понедельника не начинают: дата
     * была верна, когда её ставили, и прошла, пока черновик лежал. Иначе два дня стали бы
     * пропусками, которых не было.
     */
    @Test
    fun aDraftWhoseStartHasPassedIsNotStarted() {
        val monday = LocalDate.of(2027, 3, 8)
        val draft = prescribedDraft(schedule = schedule(start = monday), totalDoses = 7)

        val onMonday = draft.activate(Instant.parse("2027-03-08T12:00:00Z"))
        val onWednesday = draft.activate(Instant.parse("2027-03-10T12:00:00Z"))

        assertEquals(monday, onMonday.getOrThrow().course.schedule.start)
        assertEquals(CourseRejected.Reason.SCHEDULE_IN_PAST, (onWednesday.exceptionOrNull() as CourseRejected).reason)
    }

    /** «Сегодня» — в зоне расписания: в Москве уже вторник, хотя по Гринвичу ещё понедельник. */
    @Test
    fun todayIsTheScheduleZonesToday() {
        val moscowTuesday = Instant.parse("2027-03-08T22:30:00Z")

        assertTrue(schedule(start = LocalDate.of(2027, 3, 8), zone = MOSCOW).startsBefore(moscowTuesday))
        assertFalse(schedule(start = LocalDate.of(2027, 3, 9), zone = MOSCOW).startsBefore(moscowTuesday))
    }
}
