package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.prescribedDraft
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Препарат курса хранит выбранные человеком пачки, их порядок и выделение. Источник — значение:
 * тождество даёт пара (курс, пачка), приоритет — позиция (PLAN D5).
 */
class CourseMedicineTest {

    private val home = pack(id = PACK, form = TABLET_FORM, quantity = tablets("20"))
    private val dacha = pack(id = OTHER_PACK, form = TABLET_FORM, quantity = tablets("12"))

    private fun draftWithDose() = prescribedDraft()

    @Test
    fun aBoxMarkedToGoIsNotAttached() {
        // Помеченной к уходу коробкой не пользуются — и лечение на неё не опирается (PLAN E1).
        val attached = draftWithDose().attach(home.markRemoving(), doses = 5.doses, at = LATER)
        assertEquals(CourseRejected.Reason.PACKAGE_UNUSABLE, attached.rejection())
    }

    @Test
    fun attachedSourceGoesLastInTheStack() {
        // Порядок — приоритет расходования, и новая пачка встаёт после уже подключённых:
        // допить начатую и перейти к следующей — обычное намерение.
        val withTwo = draftWithDose()
            .attach(home, doses = 5.doses, at = LATER).getOrThrow()
            .attach(dacha, doses = 4.doses, at = LATER).getOrThrow()
        assertEquals(listOf(PACK, OTHER_PACK), withTwo.sources.map { it.pkg.id })
        assertEquals(listOf(5.doses, 4.doses), withTwo.sources.map { it.allocatedDoses })
        assertEquals(9.doses, withTwo.allocatedDosesTotal)
    }

    @Test
    fun changingTheListAfterwardsDoesNotChangeTheMedicine() {
        // Иначе пачка попадала бы в препарат в обход проверки уникальности и без роста редакции.
        val chosen = mutableListOf(source(PACK, 5))
        val medicine = CourseMedicine(chosen)
        chosen += source(PACK, 1)
        assertEquals(listOf(PACK), medicine.sources.map { it.pkg.id })
    }

    @Test
    fun samePackageDoesNotEnterTheStackTwice() {
        val once = draftWithDose().attach(home, doses = 5.doses, at = LATER).getOrThrow()
        val again = once.attach(home, doses = 1.doses, at = LATER)
        assertEquals(CourseRejected.Reason.ALREADY_ATTACHED, again.rejection())
    }

    @Test
    fun reorderMovesPriority() {
        val stack = draftWithDose()
            .attach(home, doses = 5.doses, at = LATER).getOrThrow()
            .attach(dacha, doses = 4.doses, at = LATER).getOrThrow()
        val swapped = stack.reorder(from = 1, to = 0, at = LATER)
        assertEquals(listOf(OTHER_PACK, PACK), swapped.sources.map { it.pkg.id })
        assertEquals(listOf(4.doses, 5.doses), swapped.sources.map { it.allocatedDoses })
    }

    @Test
    fun reorderOutsideTheStackIsAProgrammerError() {
        val stack = draftWithDose().attach(home, doses = 5.doses, at = LATER).getOrThrow()
        assertThrows(IllegalArgumentException::class.java) { stack.reorder(0, 1, LATER) }
    }

    @Test
    fun allocationOfASourceIsTheReservationInPackageUnits() {
        // Целевой объём серверной брони = выделение × доза (PLAN D5).
        val stack = draftWithDose().attach(home, doses = 5.doses, at = LATER).getOrThrow()
        assertEquals(Quantity(BigDecimal("10"), TABLETS), stack.allocatedOf(home.ref))
        assertNull(stack.allocatedOf(dacha.ref))
    }

    @Test
    fun allocationOfAPackOutsideTheMedicineIsUnknown() {
        // Выдумывать выделение пачке, которой в препарате нет, нельзя.
        val stack = draftWithDose().attach(home, doses = 5.doses, at = LATER).getOrThrow()
        assertNull(stack.allocatedOf(dacha.ref))
        assertEquals(5.doses, stack.allocatedDosesTotal)
    }

    @Test
    fun changingSourcesAgesTheRevision() {
        val attached = draftWithDose().attach(home, doses = 5.doses, at = LATER)
        assertEquals(Revision(1), attached.getOrThrow().revision)
        assertEquals(Revision(2), attached.getOrThrow().detach(home.ref, LATER).revision)
    }

    @Test
    fun sourcesOfAnActiveCourseAreStillEditable() {
        // Это не изменение назначенной дозы или календаря, поэтому менять можно (PLAN D5).
        val active = activeCourse(sources = listOf(source(PACK, 5)))
        val widened = active.attach(dacha, doses = 4.doses, at = LATER).getOrThrow()
        assertEquals(9.doses, widened.allocatedDosesTotal)
        assertEquals(schedule(), widened.schedule)
        assertEquals(dose("2"), widened.dose)
    }

    @Test
    fun activationRequiresScheduleDoseFormTotalAndSource() {
        val bare = course()
        assertEquals(CourseRejected.Reason.SCHEDULE_MISSING, bare.activate(LATER).rejection())
        val scheduled = bare.setSchedule(schedule(), LATER)
        assertEquals(CourseRejected.Reason.DOSE_MISSING, scheduled.activate(LATER).rejection())
        val dosed = scheduled.setDose(dose("2"), LATER).getOrThrow()
        assertEquals(CourseRejected.Reason.FORM_MISSING, dosed.activate(LATER).rejection())
        val formed = dosed.setForm(TABLET_FORM, LATER).getOrThrow()
        assertEquals(CourseRejected.Reason.TOTAL_DOSES_MISSING, formed.activate(LATER).rejection())
        val counted = formed.setTotalDoses(7.doses, LATER)
        // Пачки не нужно: лечение начинается и без лекарства на руках. Активация удалась — и
        // повторить её нечем: у плана этого перехода нет.
        assertTrue(counted.activate(LATER).isSuccess)
    }

    @Test
    fun courseStartedWithoutPacksIsSimplyUnsupplied() {
        // Записал у врача, куплю завтра: обеспечение «0 из N», а не отказ активировать.
        val started = prescribedDraft(schedule = schedule(), totalDoses = 7).activate(LATER).getOrThrow()
        assertTrue(started.course.medicine.isEmpty)
        val coverage = started.course.coverage(
            CourseProgress.none,
            com.kert0n.medapp.domain.pack.Availability(emptyMap())
        )
        assertEquals(7.doses, coverage.requiredDoses)
        assertEquals(0.doses, coverage.coveredDoses)
        assertEquals(schedule().next(schedule().beginning, 1).single().at, coverage.firstUncoveredAt)
    }

    @Test
    fun draftWithSourcesButNoScheduleIsRejectedForActivationNotForSaving() {
        // Черновик с выбранными пачками сохраняется: броней у него нет, упаковку он не занимает.
        val chosen = draftWithDose().attach(home, doses = 5.doses, at = LATER).getOrThrow()
        assertEquals(listOf(PACK), chosen.sources.map { it.pkg.id })
        assertEquals(CourseRejected.Reason.SCHEDULE_MISSING, chosen.activate(LATER).rejection())
    }

    @Test
    fun activationDoesNotAgeTheRevision() {
        // Активация не меняет ни расписания, ни источников: материализованным пунктам нечего
        // объявлять устаревшими.
        val ready = prescribedDraft(schedule = schedule(), totalDoses = 7)
            .attach(home, doses = 5.doses, at = LATER).getOrThrow()
        assertEquals(ready.revision, ready.activate(LATER).getOrThrow().course.revision)
    }

    @Test
    fun twoSourcesWithTheSamePackageAreNotRepresentable() {
        val duplicated = runCatching {
            prescribedDraft(sources = listOf(source(PACK, 1), source(PACK, 2)))
        }
        assertTrue(duplicated.exceptionOrNull() is IllegalArgumentException)
    }

    private fun <T> Result<T>.rejection(): CourseRejected.Reason? =
        (exceptionOrNull() as? CourseRejected)?.reason
}
