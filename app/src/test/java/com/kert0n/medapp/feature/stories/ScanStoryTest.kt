package com.kert0n.medapp.feature.stories

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.feature.scan.PackageScanning
import com.kert0n.medapp.network.crpt.CrptApi
import com.kert0n.medapp.network.crpt.CrptFixtures
import com.kert0n.medapp.network.crpt.CrptPackageCodes
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.crptHttpClient
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **J2.6 Сканирование** без камеры (PLAN J2): полный ответ реестра — предложение с формой-догадкой и
 * текстом реестра; неполный — предложение не беднее ответа; не найдено; EAN-13 — «не поддерживается»
 * **без запроса**; реестр отказал — недоступность с причиной. Камера и отказ в ней — U9.
 */
class ScanStoryTest {

    private val coated = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000102"), "таблетки покрытые пленочной оболочкой")
    private val words = Vocabulary(listOf(QuantityUnit(Uuid.parse("00000000-0000-4000-8000-000000000001"), "таблетка")), listOf(coated))

    private class Store(private val words: Vocabulary) : VocabularyStore {
        override suspend fun snapshot(): Vocabulary = words
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = error("словарь здесь не растёт")
    }

    private val asked = mutableListOf<HttpRequestData>()

    private fun scanning(answer: (HttpRequestData) -> Pair<String, HttpStatusCode>): PackageScanning {
        val crpt = CrptApi(crptHttpClient(MockEngine { request ->
            asked += request
            val (body, status) = answer(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }, "https://crpt.test"))
        val medApp = MedAppApi(medAppHttpClient(MockEngine { respond("", HttpStatusCode.NoContent) }, "https://medapp.test"))
        return PackageScanning(CrptPackageCodes(crpt, VocabularyResolver(Store(words), medApp)))
    }

    private fun dataMatrix(text: String = CrptFixtures.SCANNED) = ScannedCode(CodeFormat.DATA_MATRIX, text)

    @Test
    fun aFullAnswerBecomesASuggestionWithTheFormGuessAndTheRegistryText() = runTest {
        val outcome = scanning { CrptFixtures.found to HttpStatusCode.OK }.lookup(dataMatrix()) as PackageScanning.Outcome.Suggested

        assertEquals("Цетрин", outcome.suggestion.name)
        assertEquals(coated, outcome.suggestion.form)
        assertEquals("таблетки покрытые пленочной оболочкой", outcome.suggestion.formText)
        assertEquals(LocalDate.of(2028, 3, 31), outcome.suggestion.expiresOn?.lastDay)
        assertEquals("30 шт", outcome.suggestion.quantityText)
        assertTrue(outcome.suggestion.isMedicine)
        assertEquals(1, asked.size)
    }

    /** Ответ без блоков карточки — предложение из того, что назвали: имя и годность, остальное пусто. */
    @Test
    fun aPartialAnswerIsAPoorerSuggestionNotAFailure() = runTest {
        // `expireDate` — миллисекунды полуночи по Москве 31 марта 2028.
        val partial = """{"codeFounded": true, "productName": "Аспирин", "category": "Лекарственные препараты", "expireDate": 1838062800000}"""

        val outcome = scanning { partial to HttpStatusCode.OK }.lookup(dataMatrix()) as PackageScanning.Outcome.Suggested

        assertEquals("Аспирин", outcome.suggestion.name)
        assertEquals(LocalDate.of(2028, 3, 31), outcome.suggestion.expiresOn?.lastDay)
        assertNull(outcome.suggestion.form)
        assertNull(outcome.suggestion.formText)
        assertNull(outcome.suggestion.manufacturer)
    }

    @Test
    fun anUnknownCodeIsNotFound() = runTest {
        assertEquals(PackageScanning.Outcome.NotFound, scanning { CrptFixtures.notFound to HttpStatusCode.OK }.lookup(dataMatrix()))
        assertEquals(PackageScanning.Outcome.NotFound, scanning { "" to HttpStatusCode.NotFound }.lookup(dataMatrix()))
    }

    /** EAN-13 не наш код: «не поддерживается», и в реестр ничего не уходит — трафик бережём. */
    @Test
    fun aBarcodeIsUnsupportedWithoutAnyRequest() = runTest {
        val outcome = scanning { CrptFixtures.found to HttpStatusCode.OK }.lookup(ScannedCode(CodeFormat.OTHER, "4601234567890"))

        assertEquals(PackageScanning.Outcome.Unsupported, outcome)
        assertEquals(0, asked.size)
    }

    /** Реестр отказал по месту (`451`) — недоступность с причиной, а не «не найдено». */
    @Test
    fun aRefusedRegistryIsUnavailableWithItsReason() = runTest {
        val outcome = scanning { "" to CrptApi.UNAVAILABLE_FOR_LEGAL_REASONS }.lookup(dataMatrix())

        assertEquals(PackageScanning.Outcome.Unavailable(Unavailability.SERVER_REFUSED_US), outcome)
    }
}
