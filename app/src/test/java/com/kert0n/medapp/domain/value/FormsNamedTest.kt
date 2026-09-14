package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Форма по чужому тексту сопоставляется со словарём нормализованным именем и контролируемым
 * списком сокращений (PLAN H5): точное совпадение — одна, то же первое слово — несколько, иначе —
 * ни одной. Совпадения по названию препарата недостаточно.
 */
class FormsNamedTest {

    private val tablets = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000101"), "таблетки")
    private val coated = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000102"), "таблетки покрытые пленочной оболочкой")
    private val sublingual = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000103"), "таблетки подъязычные")
    private val capsules = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000104"), "капсулы")
    private val solutionIv = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000105"), "раствор для внутривенного введения")
    private val words = Vocabulary(emptyList(), listOf(tablets, coated, sublingual, capsules, solutionIv))

    @Test
    fun anExactNameIsOneFormRegardlessOfCaseAndPunctuation() {
        assertEquals(listOf(coated), words.formsNamed("Таблетки, покрытые плёночной оболочкой"))
        assertEquals(listOf(tablets), words.formsNamed("ТАБЛЕТКИ"))
    }

    @Test
    fun knownAbbreviationsExpand() {
        assertEquals(listOf(coated), words.formsNamed("таб., покрытые плёночной оболочкой"))
        assertEquals(listOf(solutionIv), words.formsNamed("р-р для внутривенного введения"))
    }

    /** Одно слово «таблетки» точно называет форму «таблетки» — она есть; без неё это был бы выбор. */
    @Test
    fun theFirstWordAloneOffersTheChoiceWhenNothingMatchesExactly() {
        val withoutPlain = Vocabulary(emptyList(), listOf(coated, sublingual, capsules))

        assertEquals(listOf(sublingual, coated), withoutPlain.formsNamed("таблетки"))
        assertEquals(listOf(sublingual, coated), withoutPlain.formsNamed("таблетки жевательные"))
    }

    /** Косая черта между словами — альтернатива: две формы на выбор, а не первая попавшаяся. */
    @Test
    fun aSlashBetweenWordsOffersBoth() {
        assertEquals(listOf(capsules, tablets), words.formsNamed("капсулы/таблетки"))
        // «п/о» не раскрывается — это выбор из всех «таблетки…», а не догадка о порядке слов.
        assertEquals(listOf(tablets, sublingual, coated), words.formsNamed("таблетки п/о плёночной"))
    }

    @Test
    fun anUnknownOrEmptyTextNamesNothing() {
        assertEquals(emptyList<DosageForm>(), words.formsNamed("пластырь"))
        assertEquals(emptyList<DosageForm>(), words.formsNamed("   "))
    }
}
