package com.kert0n.medapp.network.template

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.domain.template.TemplateQuery
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyStore
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск справочника на сервере (PLAN H5): десять подсказок, словарные ссылки объектами словаря, а
 * непонятное — пустым; коды ответа за границу сети не уходят.
 */
class ServerPackageTemplatesTest {

    private class Store : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    private val requests = mutableListOf<HttpRequestData>()

    private fun templates(answer: (HttpRequestData) -> Pair<HttpStatusCode, String>): ServerPackageTemplates {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    requests += request
                    if (request.url.encodedPath != "/v1/drug-templates") {
                        // Дочитывание словаря приносит тот же снимок: неизвестного он не знает.
                        return@MockEngine respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    val (status, body) = answer(request)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        return ServerPackageTemplates(api, VocabularyResolver(Store(), api))
    }

    @Test
    fun cardsArriveWithVocabularyObjectsAndTenIsAsked() = runTest {
        val found = templates {
            HttpStatusCode.OK to """[{"id":"$CARD","name":"Парацетамол","formTypeId":"${TABLET_FORM.id}",
                "quantityUnitId":"${TABLETS.id}","manufacturer":"  ","activeSubstance":"Парацетамол"}]"""
        }.search(TemplateQuery("парац"))

        val card = (found as PackageTemplates.Search.Found).templates.single()
        assertEquals(TABLET_FORM, card.facts.form)
        assertEquals(TABLETS, card.unit)
        assertNull("пустое сведение — не сведение", card.facts.manufacturer)
        val search = requests.first { it.url.encodedPath == "/v1/drug-templates" }
        assertEquals("парац", search.url.parameters["query"])
        assertEquals("10", search.url.parameters["limit"])
    }

    /** Форму, которой словарь не знает и после дочитывания, подсказка оставляет пустой. */
    @Test
    fun anUnknownFormStaysEmptyInsteadOfFailingTheSearch() = runTest {
        val found = templates {
            HttpStatusCode.OK to """[{"id":"$CARD","name":"Порошок","formTypeId":"${Uuid.random()}"}]"""
        }.search(TemplateQuery("порошок"))

        assertNull((found as PackageTemplates.Search.Found).templates.single().facts.form)
    }

    /** Карточка, которая пачкой не станет, в подсказки не попадает, а остальные — да. */
    @Test
    fun aCardThatCannotBecomeAPackageIsLeftOut() = runTest {
        val tooLong = "а".repeat(301)
        val found = templates {
            HttpStatusCode.OK to """[{"id":"$CARD","name":"$tooLong"},{"id":"${Uuid.random()}","name":"Ибупрофен"}]"""
        }.search(TemplateQuery("и"))

        assertEquals(listOf("Ибупрофен"), (found as PackageTemplates.Search.Found).templates.map { it.facts.name })
    }

    @Test
    fun nothingFoundIsAnAnswerNotAFailure() = runTest {
        val found = templates { HttpStatusCode.OK to "[]" }.search(TemplateQuery("нет такого"))

        assertEquals(PackageTemplates.Search.Found(emptyList()), found)
    }

    @Test
    fun failuresAreNamedByUnavailability() = runTest {
        assertEquals(
            PackageTemplates.Search.Unavailable(Unavailability.SERVER_REFUSED_US),
            templates { HttpStatusCode.Unauthorized to "" }.search(TemplateQuery("а"))
        )
        val offline = ServerPackageTemplates(
            MedAppApi(medAppHttpClient(MockEngine { throw IOException("связи нет") }, "https://medapp.test", retryDelay = { delayMillis(false) { 0L } })),
            VocabularyResolver(Store(), MedAppApi(medAppHttpClient(MockEngine { throw IOException() }, "https://medapp.test")))
        )
        val result = offline.search(TemplateQuery("а"))
        assertTrue("$result", result == PackageTemplates.Search.Unavailable(Unavailability.NO_CONNECTION))
    }

    private companion object {
        val CARD: Uuid = Uuid.parse("00000000-0000-4000-8000-0000000000c1")
    }
}
