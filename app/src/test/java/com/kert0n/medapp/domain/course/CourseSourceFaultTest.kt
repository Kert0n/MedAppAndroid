package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.prescribedDraft
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Источник в чужой единице или форме отключается с причиной, а не исчезает: ничего не держит и не
 * даёт, но виден; доза и форма лечения прежние (PLAN D5).
 */
class CourseSourceFaultTest {

    private val tablets = pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM).ref
    private val inMillilitres = pack(id = OTHER_PACK, quantity = millilitres("200"), form = TABLET_FORM).ref
    private val capsules = pack(id = OTHER_PACK, quantity = tablets("20"), form = CAPSULE_FORM).ref

    @Test
    fun compatibilityIsOneRuleForAttachingAndChecking() {
        val prescription = activeCourse().prescription
        assertNull(prescription.faultOf(tablets))
        assertEquals(CourseSource.Fault.UNIT_MISMATCH, prescription.faultOf(inMillilitres))
        assertEquals(CourseSource.Fault.FORM_MISMATCH, prescription.faultOf(capsules))
        assertEquals(CourseSource.Fault.FORM_MISMATCH, prescription.faultOf(pack(id = OTHER_PACK, form = null).ref))
    }

    @Test
    fun aFaultedSourceHoldsNothing() {
        assertNotNull(runCatching { CourseSource(tablets, Doses(3), CourseSource.Fault.UNIT_MISMATCH) }.exceptionOrNull())
        assertEquals(CourseSource(tablets, 0.doses, CourseSource.Fault.UNIT_MISMATCH), CourseSource(tablets, 0.doses, CourseSource.Fault.UNIT_MISMATCH))
    }

    /**
     * Отключённый источник: в обеспечении — строкой с причиной и нулями, в расходе его нет, а
     * расклад по нему считать нечем (красная проверка: считать по всем — `dosesIn` бросит на
     * чужой единице).
     */
    @Test
    fun aFaultedSourceIsVisibleButGivesNothing() {
        val course = activeCourse(totalDoses = 7, sources = listOf(source(tablets, 5), source(inMillilitres, 2)))
            .faultSource(inMillilitres, CourseSource.Fault.UNIT_MISMATCH, LATER)
        val availability = availability(PACK to tablets("20"), OTHER_PACK to millilitres("200"))

        val coverage = course.coverage(CourseProgress.none, availability)

        assertEquals(5.doses, coverage.coveredDoses)
        val faulted = coverage.perSource[1]
        assertEquals(CourseSource.Fault.UNIT_MISMATCH, faulted.fault)
        assertEquals(0.doses, faulted.allocatedDoses)
        assertEquals(0.doses, faulted.maxDoses)
        assertEquals(0.doses, course.maxDoses(inMillilitres, Doses(7), availability))
        val order = course.spendOrder(Doses(7), availability)
        assertEquals(5, order.count { it == tablets })
        assertEquals(2, order.count { it == null })
        // Зажим отключённого не трогает и второй раз причину не ставит.
        assertSame(course, course.faultSource(inMillilitres, CourseSource.Fault.UNIT_MISMATCH, LATER))
        assertEquals(course.medicine, course.clamped(Doses(7), availability, LATER).medicine)
    }

    @Test
    fun aRestoredSourceKeepsZeroAllocationAndTheCourseItsPrescription() {
        val course = activeCourse(sources = listOf(source(tablets, 5)))
        val faulted = course.faultSource(tablets, CourseSource.Fault.FORM_MISMATCH, LATER)
        assertEquals(course.prescription, faulted.prescription)
        assertEquals(course.revision.next(), faulted.revision)

        val restored = faulted.restoreSource(tablets, LATER)

        assertEquals(CourseSource(tablets, 0.doses), restored.sources.single())
        assertEquals(faulted.revision.next(), restored.revision)
        assertSame(restored, restored.restoreSource(tablets, LATER))
    }

    @Test
    fun aDraftWithAFaultedSourceDoesNotStart() {
        val draft = prescribedDraft(schedule = schedule(), totalDoses = 5, sources = listOf(source(tablets, 5)))
            .faultSource(tablets, CourseSource.Fault.UNIT_MISMATCH, LATER)

        val refused = draft.activate(LATER).exceptionOrNull()

        assertEquals(CourseRejected.Reason.UNIT_MISMATCH, (refused as CourseRejected).reason)
        assertNotNull(draft.restoreSource(tablets, LATER).activate(LATER).getOrNull())
    }
}
