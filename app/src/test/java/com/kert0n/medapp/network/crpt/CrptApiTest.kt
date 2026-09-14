package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.network.server.crptHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один вопрос «Честному знаку»: код уходит байт в байт под литеральным `{FNC1}`, без пропуска
 * MedApp; «не найдено» — обычный ответ, а не ошибка; чужая форма ответа — не провал (PLAN H5, G3).
 */
class CrptApiTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CrptApi = CrptApi(
        crptHttpClient(
            MockEngine { request ->
                requests += request
                answer(request)
            },
            "https://crpt.test"
        )
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val code = DataMatrixCode(CrptFixtures.SCANNED)

    /** Красная проверка: любая «чистка» кода — GS, пробелы, регистр — и тело не совпадёт. */
    @Test
    fun theRequestCarriesTheCodeVerbatimUnderALiteralFnc1() = runTest {
        api { json(CrptFixtures.found) }.check(code)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v2/mobile/check", request.url.encodedPath)
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        val sent = body.getValue("code").jsonPrimitive.content
        assertEquals("{FNC1}" + CrptFixtures.SCANNED, sent)
        assertTrue("разделители GS внутри кода должны остаться", sent.contains(CrptFixtures.GS + "91"))
        assertEquals("datamatrix", body.getValue("codeType").jsonPrimitive.content)
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFoundCodeYieldsTheBody() = runTest {
        val check = api { json(CrptFixtures.found) }.check(code)

        val body = (check as CrptCheck.Body).dto
        assertEquals("drugs", body.category)
        assertEquals("таблетки покрытые пленочной оболочкой", body.pharmacy?.form)
        assertEquals("Д-Р РЕДДИ`С ЛАБОРАТОРИС ЛТД.", body.attributes["Производитель"])
        assertEquals("ИНДИЯ", body.chip("country"))
        assertEquals(1838073600000L, body.expireDate)
    }

    /** `200` с признаком «не нашли» — нормальный ответ; `404` и `400` — тот же случай. */
    @Test
    fun notFoundIsAnAnswerNotAnError() = runTest {
        assertEquals(CrptCheck.NotFound, api { json(CrptFixtures.notFound) }.check(code))
        assertEquals(CrptCheck.NotFound, api { respond("", HttpStatusCode.NotFound) }.check(code))
        assertEquals(CrptCheck.NotFound, api { respond("", HttpStatusCode.BadRequest) }.check(code))
    }

    @Test
    fun aBrokenConnectionIsNoConnection() = runTest {
        assertEquals(CrptCheck.Unavailable(Unavailability.NO_CONNECTION), api { throw ConnectException("связи нет") }.check(code))
    }

    /** `451` — доступ закрыт по месту, `403` — по правилу: повтор тем же не поможет, человеку нужно решение. */
    @Test
    fun aLegalOrPolicyRefusalIsARefusalNotSilence() = runTest {
        assertEquals(CrptCheck.Unavailable(Unavailability.SERVER_REFUSED_US), api { respond("", CrptApi.UNAVAILABLE_FOR_LEGAL_REASONS) }.check(code))
        assertEquals(CrptCheck.Unavailable(Unavailability.SERVER_REFUSED_US), api { respond("", HttpStatusCode.Forbidden) }.check(code))
    }

    @Test
    fun anythingElseIsAServerThatKeptSilent() = runTest {
        assertEquals(CrptCheck.Unavailable(Unavailability.SERVER_SILENT), api { respond("", HttpStatusCode.BadGateway) }.check(code))
        assertEquals(
            CrptCheck.Unavailable(Unavailability.SERVER_SILENT),
            api { respond("<html>прокси</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.check(code)
        )
    }

    /** Ключей, которых мы не знаем, и пропавших блоков ответ не боится: меньше подсказок, а не ошибка. */
    @Test
    fun aChangedShapeStillReads() = runTest {
        val check = api {
            json("""{"codeFounded": true, "productName": "Что-то", "screen": {"items": [{"itemType": "unknown", "newField": {}}]}, "extra": 1}""")
        }.check(code)

        val body = (check as CrptCheck.Body).dto
        assertEquals("Что-то", body.productName)
        assertNull(body.pharmacy)
        assertEquals(emptyMap<String, String>(), body.attributes)
    }
}
