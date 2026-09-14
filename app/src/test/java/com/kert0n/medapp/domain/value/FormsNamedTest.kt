package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Форма по чужому тексту (PLAN H5): словарь и текст реестра получены разными путями, и совпадение
 * имён — удача. Точное имя даёт одну форму; иначе — кандидаты по основе первого слова, и
 * выбирает человек; ничего похожего — ни одной. Совпадения по названию препарата недостаточно.
 */
class FormsNamedTest {

    private val tablets = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000101"), "таблетки")
    private val coated = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000102"), "таблетки покрытые пленочной оболочкой")
    private val sublingual = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000103"), "таблетки подъязычные")
    private val capsules = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000104"), "капсулы")
    private val drops = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000106"), "капли глазные")
    private val solutionIv = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000105"), "раствор для внутривенного введения")
    private val words = Vocabulary(emptyList(), listOf(tablets, coated, sublingual, capsules, drops, solutionIv))

    @Test
    fun anExactNameIsOneFormRegardlessOfCaseAndPunctuation() {
        assertEquals(listOf(coated), words.formsNamed("Таблетки, покрытые плёночной оболочкой"))
        assertEquals(listOf(tablets), words.formsNamed("ТАБЛЕТКИ"))
    }

    /**
     * Словарь назвал форму иначе, чем реестр, — точного имени нет, и человеку предлагают выбрать
     * из форм с той же основой. Красная проверка: искать только точное имя — «Таблетки шипучие»
     * не нашли бы ничего, хотя таблетки в словаре есть.
     */
    @Test
    fun aDifferentWordingOffersTheCandidatesByStem() {
        assertEquals(listOf(tablets, sublingual, coated), words.formsNamed("ТАБЛЕТКИ ШИПУЧИЕ"))
        assertEquals(listOf(tablets, sublingual, coated), words.formsNamed("таблетка"))
    }

    /** Сокращения ловятся основой без списка синонимов; «р-р» — единственное исключение. */
    @Test
    fun abbreviationsAreCaughtByTheStem() {
        assertEquals(listOf(tablets, sublingual, coated), words.formsNamed("табл. п/о"))
        assertEquals(listOf(capsules), words.formsNamed("капс."))
        assertEquals(listOf(solutionIv), words.formsNamed("р-р для внутривенного введения"))
        // Основа «капли» не ловит «капсулы» — и наоборот.
        assertEquals(listOf(drops), words.formsNamed("капли"))
    }

    /** Косая черта между словами — альтернатива: обе формы на выбор, а не первая попавшаяся. */
    @Test
    fun aSlashBetweenWordsOffersBoth() {
        assertEquals(listOf(capsules, tablets), words.formsNamed("капсулы/таблетки"))
    }

    @Test
    fun anUnknownShortOrEmptyTextNamesNothing() {
        assertEquals(emptyList<DosageForm>(), words.formsNamed("пластырь"))
        assertEquals(emptyList<DosageForm>(), words.formsNamed("т."))
        assertEquals(emptyList<DosageForm>(), words.formsNamed("   "))
    }
}
