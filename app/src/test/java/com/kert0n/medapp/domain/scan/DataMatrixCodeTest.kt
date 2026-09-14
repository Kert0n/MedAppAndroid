package com.kert0n.medapp.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код с коробки не разбирается и не нормализуется (PLAN H5): что отдал сканер, то и уходит, под
 * литеральным `{FNC1}` из шести символов. Красная проверка: любая «чистка» — и `wire` разойдётся
 * с текстом.
 */
class DataMatrixCodeTest {

    private val gs = "\u001d"

    @Test
    fun theWireIsTheTextVerbatimUnderALiteralPrefix() {
        val text = "0104601234567890215ABCDE12345" + gs + "91EE11" + gs + "92dGVzdA=="

        val wire = DataMatrixCode(text).wire

        assertEquals("{FNC1}$text", wire)
        assertEquals(6, DataMatrixCode.FNC1.length)
        assertTrue("GS внутри кода должен остаться", wire.contains(gs + "91"))
    }

    /** Ведущий GS, пробелы, регистр, AIM-идентификатор — всё остаётся: чистить нечем и незачем. */
    @Test
    fun nothingIsStrippedOrChanged() {
        for (text in listOf(gs + "0104601234567890215A", "]d20104601234567890215A", " 01 0460 ", "abc DEF")) {
            assertEquals("{FNC1}$text", DataMatrixCode(text).wire)
        }
    }

    @Test
    fun anEmptyCodeIsRefused() {
        assertTrue(runCatching { DataMatrixCode("") }.exceptionOrNull() is IllegalArgumentException)
    }
}
