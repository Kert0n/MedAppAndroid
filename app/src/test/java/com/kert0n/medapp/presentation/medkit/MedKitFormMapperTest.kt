package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.presentation.ParsedInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** Разбор формы аптечки: строки экрана — в то, что примет сценарий (PLAN H3 №3). */
class MedKitFormMapperTest {

    private fun form(name: String, location: String = "") = MedKitFormPresentationDTO(name, location)

    /**
     * Пробелы вокруг названия — не часть его: человек их не видит, а домен отличил бы «Дом» от
     * «Дом ».
     */
    @Test
    fun spacesAroundTheNameAreNotPartOfIt() {
        val parsed = form("  Домашняя  ", "  Верхняя полка  ").parsed()

        assertEquals(
            ParsedInput.Parsed(MedKitDescription("Домашняя", "Верхняя полка")),
            parsed
        )
    }

    /**
     * Пустое место хранения — **отсутствие**, а не пустая строка: пустая означала бы, что
     * человек сказал «места нет».
     */
    @Test
    fun anEmptyLocationIsAbsenceAndNotAnEmptyString() {
        val parsed = form("Дача", "   ").parsed()

        assertEquals(ParsedInput.Parsed(MedKitDescription("Дача", null)), parsed)
    }

    /** Без названия аптечку не отличить от других — и это отказ, названный своим полем. */
    @Test
    fun aNameIsRequiredAndTheRefusalNamesItsField() {
        assertEquals(ParsedInput.Rejected(MedKitFormError.Input.NAME_EMPTY), form("   ").parsed())
    }

    /**
     * Предел берётся у самой аптечки: повтори его здесь числом — и правило разъедется с тем, о
     * чём оно.
     */
    @Test
    fun tooLongNameAndLocationAreRefusedByTheirOwnLimits() {
        assertEquals(
            ParsedInput.Rejected(MedKitFormError.Input.NAME_TOO_LONG),
            form("я".repeat(MedKit.NAME_MAX_LENGTH + 1)).parsed()
        )
        assertEquals(
            ParsedInput.Rejected(MedKitFormError.Input.LOCATION_TOO_LONG),
            form("Дача", "я".repeat(MedKit.LOCATION_MAX_LENGTH + 1)).parsed()
        )
    }
}
