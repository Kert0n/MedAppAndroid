package com.kert0n.medapp.network.marking

import com.kert0n.medapp.domain.scan.DataMatrixCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код уходит в поле `code` **байт в байт** под литеральным `{FNC1}` из шести символов (PLAN H5,
 * C1): префикс — формат реестра, и кладёт его сетевая граница. Красная проверка: любая «чистка»
 * или управляющий байт вместо литерала — и тело разойдётся с текстом сканера.
 */
class MarkingCheckRequestNetworkDTOTest {

    private val gs = "\u001d"

    @Test
    fun theCodeGoesVerbatimUnderALiteralPrefix() {
        val text = "0104601234567890215ABCDE12345" + gs + "91EE11" + gs + "92dGVzdA=="

        val request = MarkingCheckRequestNetworkDTO.of(DataMatrixCode(text))

        assertEquals("{FNC1}$text", request.code)
        assertEquals(6, MarkingCheckRequestNetworkDTO.FNC1.length)
        assertEquals("datamatrix", request.codeType)
        assertTrue("GS внутри кода должен остаться", request.code.contains(gs + "91"))
    }

    @Test
    fun nothingIsStrippedOrChanged() {
        for (text in listOf(gs + "0104601234567890215A", "]d20104601234567890215A", " 01 0460 ", "abc DEF")) {
            assertEquals("{FNC1}$text", MarkingCheckRequestNetworkDTO.of(DataMatrixCode(text)).code)
        }
    }
}
