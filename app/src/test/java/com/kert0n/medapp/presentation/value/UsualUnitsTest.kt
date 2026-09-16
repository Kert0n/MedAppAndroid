package com.kert0n.medapp.presentation.value

import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.suggestingUnit
import com.kert0n.medapp.presentation.course.withForm
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Форма выпуска подсказывает единицу: таблетки считают штуками, сироп — миллилитрами. Подсказка,
 * а не правило: названную человеком единицу форма не переписывает, а незнакомая форма молчит.
 */
class UsualUnitsTest {

    private val pieces = UnitPresentationDTO(Uuid.random(), "шт")
    private val millilitres = UnitPresentationDTO(Uuid.random(), "мл")
    private val grams = UnitPresentationDTO(Uuid.random(), "г")
    private val units = listOf(pieces, millilitres, grams)

    private fun form(name: String) = FormPresentationDTO(Uuid.random(), name)

    /** Штучное считают штуками: без пары «таблетки → шт» человек набирает единицу руками каждый раз. */
    @Test
    fun tabletsAreCountedInPieces() {
        assertEquals(pieces, form("таблетки").usualUnit(units))
        assertEquals(pieces, form("капсулы").usualUnit(units))
    }

    /** Жидкое меряют миллилитрами: подсказка «шт» у сиропа хуже пустого поля — её пришлось бы стирать. */
    @Test
    fun liquidsAreMeasuredInMillilitres() {
        assertEquals(millilitres, form("сироп").usualUnit(units))
        assertEquals(millilitres, form("капли").usualUnit(units))
    }

    /** Мазь меряют граммами: своя пара у каждой уверенной формы, иначе подсказка молчит там, где могла бы помочь. */
    @Test
    fun ointmentsAreMeasuredInGrams() {
        assertEquals(grams, form("мазь").usualUnit(units))
    }

    /** Спорному подсказывать нечего: порошок бывает и в граммах, и в саше. */
    @Test
    fun anAmbiguousFormSuggestsNothing() {
        assertNull(form("порошок").usualUnit(units))
        assertNull(form("другие").usualUnit(units))
    }

    /** Единицы нет в словаре этого сервера — подставлять нечего, и выдумывать нельзя. */
    @Test
    fun aUnitMissingFromTheVocabularyIsNotInvented() {
        assertNull(form("таблетки").usualUnit(listOf(millilitres)))
    }

    /** Имена приходят из словаря сервера: регистр и пробелы вокруг сопоставлению не мешают. */
    @Test
    fun theNameIsMatchedRegardlessOfCaseAndSpaces() {
        assertEquals(pieces, form(" Таблетки ").usualUnit(units))
    }

    /** Выбрал форму — единица встала сама: поле было пустым, и мерить этим будут в любом случае. */
    @Test
    fun choosingAFormFillsAnEmptyUnit() {
        val chosen = CourseFormPresentationDTO(title = "Нурофен").withForm(form("таблетки"), units)

        assertEquals(pieces, chosen.unit)
        assertEquals("таблетки", chosen.form?.name)
    }

    /**
     * Записанный черновик открыт заново: единицы у него нет — её негде хранить, пока нет числа, —
     * и подсказку приносит его собственная форма выпуска, а не пустое поле (H3 §15).
     */
    @Test
    fun aStoredFormBringsItsUnitBackWhenTheVocabularyArrives() {
        val reopened = CourseFormPresentationDTO(title = "Нурофен", form = form("таблетки"))

        assertEquals(pieces, reopened.suggestingUnit(units).unit)
    }

    /** Назвал единицу сам — форма её не трогает: обратный порядок ничего не меняет. */
    @Test
    fun aUnitChosenByThePersonIsLeftAlone() {
        val chosen = CourseFormPresentationDTO(title = "Нурофен", doseAmount = "2", unit = millilitres)

        val filled = chosen.withForm(form("таблетки"), units)

        assertEquals(millilitres, filled.unit)
        assertEquals("таблетки", filled.form?.name)
    }
}
