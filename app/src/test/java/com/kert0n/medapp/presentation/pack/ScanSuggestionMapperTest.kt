package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Предложение кода — в поля обычной формы новой коробки (PLAN H3 «Набор сканера»). Берётся **всё**,
 * что реестр сказал: у каждого сведения своё поле, а чему поля нет — то в описании его же словами.
 */
class ScanSuggestionMapperTest {

    private val empty = PackageFormPresentationDTO()

    /** Форма, узнанная словарём, едет в поле: выбирать её человеку незачем. */
    @Test
    fun aFormKnownToTheVocabularyGoesIntoTheField() {
        val suggestion = PackageSuggestion(
            formText = "таблетки, покрытые оболочкой",
            form = TABLET_FORM,
            isMedicine = true
        )

        assertEquals(TABLET_FORM.id, suggestion.filling(empty, VOCABULARY).form?.id)
    }

    /**
     * Форму, которой словарь не знает, приложение не выдумывает: поле остаётся за человеком.
     * Подставь наугад — и коробка не подошла бы к лечению по форме, а человек не понял бы почему.
     * Сказанное реестром при этом не пропадает — оно уходит в описание.
     */
    @Test
    fun anUnknownFormLeavesTheFieldEmptyButIsNotLost() {
        val suggestion = PackageSuggestion(formText = "лиофилизат", form = null, isMedicine = true)

        val filled = suggestion.filling(empty, VOCABULARY)

        assertNull(filled.form)
        assertEquals("лиофилизат", filled.description)
    }

    /** Срок реестра показывается так же, как его печатают на коробке: месяцем. */
    @Test
    fun theExpiryIsShownTheWayItIsPrinted() {
        val suggestion = PackageSuggestion(
            expiresOn = ExpiryDate.of(YearMonth.of(2027, 5)),
            isMedicine = true
        )

        assertEquals("05.2027", suggestion.filling(empty, VOCABULARY).expiresOn)
    }

    /**
     * «30 таблетка» — это тридцать таблеток, и переписывать их человеку незачем: реестр называет
     * количество именно так («30 шт» у живых ответов). Единица берётся из словаря по её имени —
     * своей клиент не заводит.
     */
    @Test
    fun aPlainQuantityBecomesTheNumberAndItsUnit() {
        val suggestion = PackageSuggestion(quantityText = "30 ${TABLETS.name}", isMedicine = true)

        val filled = suggestion.filling(empty, VOCABULARY)

        assertEquals("30", filled.amount)
        assertEquals(TABLETS.id, filled.unit?.id)
        assertEquals("", filled.description)
    }

    /**
     * Единицу, которой словарь не знает, приложение не заводит — но число не теряет: остаток у
     * коробки один, каким бы словом его ни назвали, и единицу выберет человек.
     */
    @Test
    fun anUnknownUnitStillLeavesTheNumber() {
        val suggestion = PackageSuggestion(quantityText = "20 капсул", isMedicine = true)

        val filled = suggestion.filling(empty, VOCABULARY)

        assertEquals("20", filled.amount)
        assertNull(filled.unit)
    }

    /**
     * «20 таблеток в 2 блистерах» числом не становится: чисел там два, и ни одно не остаток
     * коробки. Подставь любое — и коробка заведётся с количеством, которого в ней нет (PLAN H5).
     * Сама фраза при этом остаётся в описании: она говорит, как лекарство упаковано.
     */
    @Test
    fun aCompositeQuantityDoesNotBecomeANumber() {
        val suggestion = PackageSuggestion(quantityText = "20 таблеток в 2 блистерах", isMedicine = true)

        val filled = suggestion.filling(empty, VOCABULARY)

        assertEquals("", filled.amount)
        assertNull(filled.unit)
        assertEquals("20 таблеток в 2 блистерах", filled.description)
    }

    /**
     * Действующее вещество и дозировку реестр называет, а полей у них нет — они уходят в описание
     * его же словами. Дозировка **разовой дозой не становится** никогда: «10 мг» это состав
     * таблетки, а не то, сколько их пить (PLAN H5).
     */
    @Test
    fun theSubstanceAndDosageGoIntoTheDescriptionAndNeverIntoTheDose() {
        val suggestion = PackageSuggestion(
            activeSubstance = "цетиризин",
            dosageText = "10 мг",
            isMedicine = true
        )

        val filled = suggestion.filling(empty, VOCABULARY)

        assertEquals("цетиризин, 10 мг", filled.description)
        assertEquals("", filled.hintAmount)
    }

    /**
     * Ответ приходит по сети, и набранное человеком старше его. Затри — и форма спорила бы с тем,
     * кто держит коробку в руках.
     */
    @Test
    fun whatIsAlreadyTypedIsKept() {
        val typed = empty.copy(name = "Цетиризин", country = "Россия", amount = "7", description = "своё")
        val suggestion = PackageSuggestion(
            name = "Цетрин",
            country = "Индия",
            quantityText = "30 ${TABLETS.name}",
            activeSubstance = "цетиризин",
            isMedicine = true
        )

        val filled = suggestion.filling(typed, VOCABULARY)

        assertEquals("Цетиризин", filled.name)
        assertEquals("Россия", filled.country)
        assertEquals("7", filled.amount)
        assertEquals("своё", filled.description)
    }
}
