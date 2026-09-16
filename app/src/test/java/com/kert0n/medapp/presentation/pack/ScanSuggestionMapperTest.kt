package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.fixture.TABLET_FORM
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Предложение кода — в поля обычной формы новой коробки (PLAN H3 «Набор сканера»). Подставляется
 * то, что реестр назвал однозначно; остального форма не выдумывает.
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

        assertEquals(TABLET_FORM.id, suggestion.filling(empty).form?.id)
    }

    /**
     * Форму, которой словарь не знает, приложение не выдумывает: поле остаётся за человеком.
     * Подставь наугад — и коробка не подошла бы к лечению по форме, а человек не понял бы почему.
     */
    @Test
    fun anUnknownFormLeavesTheFieldEmpty() {
        val suggestion = PackageSuggestion(formText = "лиофилизат", form = null, isMedicine = true)

        assertNull(suggestion.filling(empty).form)
    }

    /** Срок реестра показывается так же, как его печатают на коробке: месяцем. */
    @Test
    fun theExpiryIsShownTheWayItIsPrinted() {
        val suggestion = PackageSuggestion(
            expiresOn = ExpiryDate.of(YearMonth.of(2027, 5)),
            isMedicine = true
        )

        assertEquals("05.2027", suggestion.filling(empty).expiresOn)
    }

    /**
     * Количество реестр называет словами — «20 таблеток в 2 блистерах». Числом это не становится:
     * подставь его — и коробка завелась бы с выдуманным остатком (PLAN H5).
     */
    @Test
    fun aQuantityInWordsDoesNotBecomeANumber() {
        val suggestion = PackageSuggestion(
            quantityText = "20 таблеток в 2 блистерах",
            dosageText = "10 мг",
            isMedicine = true
        )

        assertEquals("", suggestion.filling(empty).amount)
    }

    /**
     * Ответ приходит по сети, и набранное человеком старше его. Затри — и форма спорила бы с тем,
     * кто держит коробку в руках.
     */
    @Test
    fun whatIsAlreadyTypedIsKept() {
        val typed = empty.copy(name = "Цетиризин", country = "Россия")
        val suggestion = PackageSuggestion(name = "Цетрин", country = "Индия", isMedicine = true)

        val filled = suggestion.filling(typed)

        assertEquals("Цетиризин", filled.name)
        assertEquals("Россия", filled.country)
    }
}
