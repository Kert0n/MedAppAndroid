package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.ParsedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор формы полки: строка человека становится значением для домена — или названной причиной,
 * по которой она им не стала (PLAN H1). Исключений тут не бывает ни на каком вводе: неверный ввод
 * это обычное состояние формы, а не сбой программы (REQ-047, REQ-051).
 */
class MedKitFormMapperTest {

    private fun parse(name: String = "Домашняя", location: String = "") =
        MedKitFormPresentationDTO(name, location).parsed()

    @Test
    fun aNameAndAPlaceGoThroughAsTheyAre() {
        val parsed = parse(name = "Домашняя", location = "Верхняя полка")

        assertEquals(MedKitDescription("Домашняя", "Верхняя полка"), parsed.valueOrNull)
    }

    /** Пробелы вокруг названия — не часть его: «Дача » и «Дача» это одна и та же полка. */
    @Test
    fun spacesAroundANameAreNotPartOfIt() {
        assertEquals("Дача", parse(name = "  Дача  ").valueOrNull?.name)
    }

    /**
     * Пустое место хранения — отсутствие места, а не пустая строка: домен различает «не указано»
     * и «указано», и пустая строка сказала бы неправду.
     */
    @Test
    fun anEmptyPlaceMeansThereIsNone() {
        assertNull(parse(location = "   ").valueOrNull?.location)
    }

    /** Название из одних пробелов — не название. */
    @Test
    fun aNameOfSpacesAloneIsNotAName() {
        assertEquals(MedKitFormError.Input.NAME_EMPTY, parse(name = "   ").errorOrNull)
    }

    @Test
    fun anEmptyNameIsRefusedByItsOwnField() {
        val rejected = parse(name = "") as ParsedInput.Rejected

        assertEquals(MedKitFormError.Field.NAME, rejected.error.field)
    }

    /**
     * Пределы — те же, что держит домен: форма берёт их у [MedKit], а не повторяет числом.
     * Красная проверка: увеличить предел в форме — эта проверка краснеет, а домен бросил бы на
     * записи, и форма уронила бы приложение вместо отказа.
     */
    @Test
    fun theLimitsAreTheOnesTheDomainHolds() {
        val longName = "н".repeat(MedKit.NAME_MAX_LENGTH + 1)
        val longPlace = "м".repeat(MedKit.LOCATION_MAX_LENGTH + 1)

        assertEquals(MedKitFormError.Input.NAME_TOO_LONG, parse(name = longName).errorOrNull)
        assertEquals(MedKitFormError.Input.LOCATION_TOO_LONG, parse(location = longPlace).errorOrNull)
        assertNull(parse(name = "н".repeat(MedKit.NAME_MAX_LENGTH)).errorOrNull)
    }

    /** Открытая на правку форма показывает записанное, а не пустые поля. */
    @Test
    fun aFormOpenedForEditingShowsWhatIsStored() {
        val stored = medKit(name = "Дача", location = "Сарай").projection(MedKitContents.EMPTY)

        assertEquals(
            MedKitFormPresentationDTO(name = "Дача", location = "Сарай"),
            stored.toFormPresentationDTO()
        )
    }

    /** Полка без места хранения открывается с пустым полем, а не со строкой «null». */
    @Test
    fun aPlaceThatWasNeverGivenOpensEmpty() {
        val stored = medKit(location = null).projection(MedKitContents.EMPTY)

        assertEquals("", stored.toFormPresentationDTO().location)
    }
}
