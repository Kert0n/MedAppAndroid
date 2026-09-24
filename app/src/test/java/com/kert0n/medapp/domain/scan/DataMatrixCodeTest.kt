package com.kert0n.medapp.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код с коробки не разбирается и не нормализуется (PLAN H5): что отдал сканер, то величина и
 * хранит. Как он уходит на провод, знает `network/marking` — здесь о `{FNC1}` ничего.
 */
class DataMatrixCodeTest {

    private val gs = "\u001d"

    /** Ведущий GS, пробелы, регистр, AIM-идентификатор — всё остаётся: чистить нечем и незачем. */
    @Test
    fun nothingIsStrippedOrChanged() {
        for (text in listOf("0104601234567890215ABCDE12345" + gs + "91EE11" + gs + "92dGVzdA==", gs + "0104601234567890215A", "]d20104601234567890215A", " 01 0460 ", "abc DEF")) {
            assertEquals(text, DataMatrixCode(text).text)
        }
    }

    @Test
    fun anEmptyCodeIsRefused() {
        assertTrue(runCatching { DataMatrixCode("") }.exceptionOrNull() is IllegalArgumentException)
    }
}
