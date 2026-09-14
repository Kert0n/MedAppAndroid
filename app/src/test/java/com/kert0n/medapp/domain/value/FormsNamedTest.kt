package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Форма по чужому тексту (PLAN H5): словарь и текст реестра получены разными путями, и
 * дословное совпадение — удача. Сравниваются основы слов: те же случаи, что знал
 * `scrapper/form_types.py`, — окончания, запятые, сокращения с чертой, «для приготовления»,
 * «нанесения на», дозировка после формы. Совпали целиком — она; иначе лучшая догадка, одна: она
 * подставляется сразу, а ошибётся — человек поправит.
 */
class FormsNamedTest {

    private fun form(n: Int, name: String) = DosageForm(Uuid.parse("00000000-0000-4000-8000-0000000001%02d".format(n)), name)

    private val tablets = form(1, "таблетки")
    private val coated = form(2, "таблетки покрытые пленочной оболочкой")
    private val sublingual = form(3, "таблетки подъязычные")
    private val capsules = form(4, "капсулы")
    private val drops = form(5, "капли глазные")
    private val solutionIv = form(6, "раствор для внутривенного введения")
    private val solutionIm = form(7, "раствор для внутримышечного введения")
    private val granules = form(8, "гранулы для суспензии")
    private val mouthSpray = form(9, "аэрозоль для слизистой рта")
    private val words = Vocabulary(emptyList(), listOf(tablets, coated, sublingual, capsules, drops, solutionIv, solutionIm, granules, mouthSpray))

    /** Регистр, «ё», запятые и окончания ничего не значат; дозировка после формы — не форма. */
    @Test
    fun wordingDifferencesStillNameTheOneForm() {
        assertEquals(coated, words.formNamed("ТАБЛЕТКИ, ПОКРЫТЫЕ ПЛЁНОЧНОЙ ОБОЛОЧКОЙ, 10 мг"))
        assertEquals(coated, words.formNamed("таблетка покрытая пленочной оболочкой"))
        assertEquals(tablets, words.formNamed("Таблетки 250 мг №50"))
        assertEquals(solutionIv, words.formNamed("раствор для внутривенного введения 5 мг/мл"))
    }

    /** Сокращения с чертой и с точкой — те же случаи, что у приводившего словарь скрипта. */
    @Test
    fun abbreviationsExpandLikeTheScraperDid() {
        assertEquals(coated, words.formNamed("табл. п/пл/о"))
        assertEquals(solutionIv, words.formNamed("р-р д/в/в"))
        assertEquals(solutionIm, words.formNamed("Р-Р В/М"))
        assertEquals(capsules, words.formNamed("капс."))
    }

    /** Слова-наполнители: «для приготовления суспензии» и «для суспензии» — одно; «нанесения на слизистую оболочку полости рта» — «слизистой рта». */
    @Test
    fun fillerWordsDoNotCount() {
        assertEquals(granules, words.formNamed("гранулы для приготовления суспензии"))
        assertEquals(mouthSpray, words.formNamed("аэрозоль для нанесения на слизистую оболочку полости рта"))
    }

    /**
     * Словарь назвал форму иначе — подставляется лучшая догадка: самая длинная форма, с которой
     * текст начинается; иначе самая короткая, которая начинается с текста; иначе с той же основой
     * первого слова. Ошибётся — человек поправит. Красная проверка: отвечать только точным
     * совпадением — «таблетки шипучие» остались бы без формы, хотя это таблетки.
     */
    @Test
    fun aDifferentFormGetsTheBestGuess() {
        assertEquals(tablets, words.formNamed("ТАБЛЕТКИ ШИПУЧИЕ"))
        assertEquals(coated, words.formNamed("таблетки покрытые пленочной оболочкой кишечнорастворимые"))
        assertEquals(drops, words.formNamed("капли"))
        assertEquals(solutionIv, words.formNamed("раствор для инфузий"))
    }

    /** Косая черта между словами — альтернативы: догадка по первой, которая что-то дала. */
    @Test
    fun aSlashBetweenWordsGuessesByTheFirstKnownSide() {
        assertEquals(capsules, words.formNamed("капсулы/таблетки"))
        assertEquals(tablets, words.formNamed("пластырь/таблетки"))
    }

    @Test
    fun anUnknownShortOrEmptyTextNamesNothing() {
        assertNull(words.formNamed("пластырь"))
        assertNull(words.formNamed("т."))
        assertNull(words.formNamed("   "))
    }
}
