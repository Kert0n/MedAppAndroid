package com.kert0n.medapp.feature.scan

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.scan.ScannedCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Код даёт предложение, а не факт (PLAN H5): спрашивают только о DataMatrix, EAN-13 и QR не
 * отправляются вовсе, «не найдено» — обычный исход, и на один код — один вопрос.
 */
class PackageScanningTest {

    private class Registry(private val answer: PackageCodes.Lookup) : PackageCodes {
        val asked = mutableListOf<DataMatrixCode>()
        override suspend fun lookup(code: DataMatrixCode): PackageCodes.Lookup {
            asked += code
            return answer
        }
    }

    private val suggestion = PackageSuggestion(name = "Ибупрофен", isMedicine = true)
    private val text = "0104601234567890215ABCDE12345\u001d91EE11\u001d92dGVzdA=="

    @Test
    fun aDataMatrixIsAskedOnceAndAnsweredWithASuggestion() = runTest {
        val registry = Registry(PackageCodes.Lookup.Found(suggestion))

        val outcome = PackageScanning(registry).lookup(ScannedCode(CodeFormat.DATA_MATRIX, text))

        assertEquals(PackageScanning.Outcome.Suggested(suggestion), outcome)
        assertEquals(listOf(DataMatrixCode(text)), registry.asked)
    }

    /** EAN-13 и QR — не про эту коробку: запроса нет (**красная**: спрашивать по любому формату). */
    @Test
    fun otherFormatsAreUnsupportedWithoutAQuestion() = runTest {
        val registry = Registry(PackageCodes.Lookup.Found(suggestion))
        val scanning = PackageScanning(registry)

        assertEquals(PackageScanning.Outcome.Unsupported, scanning.lookup(ScannedCode(CodeFormat.OTHER, "4601234567890")))
        assertEquals(PackageScanning.Outcome.Unsupported, scanning.lookup(ScannedCode(CodeFormat.QR, "medapp://join/abc")))
        assertEquals(PackageScanning.Outcome.Unsupported, scanning.lookup(ScannedCode(CodeFormat.DATA_MATRIX, "")))
        assertEquals(emptyList<DataMatrixCode>(), registry.asked)
    }

    @Test
    fun notFoundAndUnavailableAreTheirOwnOutcomes() = runTest {
        assertEquals(PackageScanning.Outcome.NotFound, PackageScanning(Registry(PackageCodes.Lookup.NotFound)).lookup(ScannedCode(CodeFormat.DATA_MATRIX, text)))
        assertEquals(
            PackageScanning.Outcome.Unavailable(Unavailability.NO_CONNECTION),
            PackageScanning(Registry(PackageCodes.Lookup.Unavailable(Unavailability.NO_CONNECTION))).lookup(ScannedCode(CodeFormat.DATA_MATRIX, text))
        )
    }
}
