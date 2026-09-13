package com.kert0n.medapp.domain.course

import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.prescribedDraft
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.doses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форму и единицу задаёт назначение, собранное из словаря; пачка сверяется с ним, а не с первой
 * пачкой, и отвязка пачек назначения не трогает (PLAN D5).
 */
class CourseSourceFormTest {

    private val tabletPack = pack(id = PACK, form = TABLET_FORM, quantity = tablets("20"))

    @Test
    fun prescriptionIsAssembledWithoutAnyPack() {
        // Человек записал у врача: две таблетки. Пачки ещё нет, а доза с единицей и форма уже есть.
        val prescribed = course()
            .setDose(dose("2"), LATER).getOrThrow()
            .setForm(TABLET_FORM, LATER).getOrThrow()
        assertEquals(dose("2"), prescribed.dose)
        assertEquals(TABLETS, prescribed.unit)
        assertEquals(TABLET_FORM, prescribed.form)
        assertTrue(prescribed.medicine.isEmpty)
    }

    @Test
    fun packIsCheckedAgainstThePrescriptionNotAgainstTheFirstPack() {
        // Капсулы отвергает назначение «таблетки» — и первой пачкой, а не только второй.
        val capsules = pack(id = OTHER_PACK, form = CAPSULE_FORM, quantity = tablets("10"))
        assertEquals(
            CourseRejected.Reason.FORM_MISMATCH,
            prescribedDraft().attach(capsules, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test
    fun packageWithoutAFormIsAttachedToNothing() {
        // Сказать, тот ли это препарат, нечем: сначала форму пачки надо заполнить. Экран так и
        // говорит: «укажите форму, чтобы подключить к курсу».
        val unknownForm = pack(id = OTHER_PACK, form = null)
        assertEquals(
            CourseRejected.Reason.FORM_UNKNOWN,
            prescribedDraft().attach(unknownForm, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test
    fun incompatibleUnitIsRejected() {
        // Форма та же, единица другая: доза курса измеряется единицей курса, и миллилитры в
        // «две таблетки» не подставятся.
        val syrup = pack(id = OTHER_PACK, form = TABLET_FORM, quantity = millilitres("100"))
        assertEquals(
            CourseRejected.Reason.UNIT_MISMATCH,
            prescribedDraft().attach(syrup, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test
    fun packCannotBeAttachedBeforeTheDoseAndFormAreNamed() {
        // Сверять не с чем — и отказ говорит, чего не хватает.
        assertEquals(
            CourseRejected.Reason.DOSE_MISSING,
            course().attach(tabletPack, doses = 1.doses, at = LATER).rejection()
        )
        assertEquals(
            CourseRejected.Reason.FORM_MISSING,
            course(dose = dose("2")).attach(tabletPack, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test
    fun doseInTabletsIsNotRereadInMillilitresWhenThePackChanges() {
        // Пачку отвязали и подключили другую: доза так и осталась «две таблетки». Раньше первая
        // пачка задавала единицу, и черновик начинал заново уже в миллилитрах — назначение этого
        // не допускает.
        val syrup = pack(id = OTHER_PACK, form = TABLET_FORM, quantity = millilitres("10"))
        val restarted = prescribedDraft()
            .attach(tabletPack, doses = 5.doses, at = LATER).getOrThrow()
            .detach(tabletPack.ref, LATER)
        assertEquals(dose("2"), restarted.dose)
        assertEquals(TABLETS, restarted.unit)
        assertEquals(
            CourseRejected.Reason.UNIT_MISMATCH,
            restarted.attach(syrup, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test
    fun changingTheDoseUnitUnderAttachedPacksIsRejected() {
        // Подключённые таблетки под миллилитры не годятся: сначала отвязать, потом менять.
        val chosen = prescribedDraft().attach(tabletPack, doses = 5.doses, at = LATER).getOrThrow()
        assertEquals(
            CourseRejected.Reason.UNIT_MISMATCH,
            chosen.setDose(dose(millilitres("5")), LATER).rejection()
        )
        assertEquals(
            CourseRejected.Reason.FORM_MISMATCH,
            chosen.setForm(CAPSULE_FORM, LATER).rejection()
        )
        // А число дозы в той же единице — пожалуйста.
        assertEquals(dose("3"), chosen.setDose(dose("3"), LATER).getOrThrow().dose)
    }

    @Test
    fun detachingTheLastSourceOfAnActiveCourseKeepsThePrescription() {
        // Курс просто становится необеспеченным: чем лечатся, задано врачом, а не пачкой.
        val active = activeCourse(sources = listOf(source(PACK, 5)))
        val unsupplied = active.detach(tabletPack.ref, LATER)
        assertEquals(emptyList<CourseSource>(), unsupplied.sources)
        assertEquals(TABLET_FORM, unsupplied.form)
        assertEquals(TABLETS, unsupplied.unit)
        assertEquals(dose("2"), unsupplied.dose)
    }

    @Test
    fun unsuppliedActiveCourseStillDemandsItsOwnFormBack() {
        val capsules = pack(id = OTHER_PACK, form = CAPSULE_FORM, quantity = tablets("10"))
        val unsupplied = activeCourse(sources = listOf(source(PACK, 5))).detach(tabletPack.ref, LATER)
        assertEquals(
            CourseRejected.Reason.FORM_MISMATCH,
            unsupplied.attach(capsules, doses = 1.doses, at = LATER).rejection()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun draftWithPacksButWithoutADoseIsNotRepresentable() {
        // Пачка проходит в препарат только через сверку с дозой и формой.
        course(sources = listOf(source(PACK, 5)))
    }

    private fun <T> Result<T>.rejection(): CourseRejected.Reason? =
        (exceptionOrNull() as? CourseRejected)?.reason
}
