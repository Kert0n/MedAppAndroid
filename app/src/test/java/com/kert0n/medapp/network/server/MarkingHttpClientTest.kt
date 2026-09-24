package com.kert0n.medapp.network.server

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чужой API разбирается нестрого, а заголовка авторизации MedApp у его клиента нет (PLAN G3).
 */
class MarkingHttpClientTest {

    @Serializable
    private data class Card(val name: String)

    @Test
    fun unknownFieldsOfForeignApiAreTolerated() = runTest {
        val client = markingHttpClient(
            MockEngine {
                respond(
                    """{"name":"Аспирин","gtin":"04601234567890","extra":{"any":1}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            },
            "https://marking.test"
        )

        assertEquals("Аспирин", client.get("/card").body<Card>().name)
    }

    @Test
    fun foreignHostNeverSeesAuthorization() = runTest {
        var authorization: String? = "не проверено"
        val client = markingHttpClient(
            MockEngine { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                respond("", HttpStatusCode.OK)
            },
            "https://marking.test"
        )

        client.get("/card")

        assertNull(authorization)
    }
}
