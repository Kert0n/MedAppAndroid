package com.kert0n.medapp.network.value

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyMiss
import com.kert0n.medapp.domain.value.VocabularyStore
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Промах по снимку — «снимок устарел», и лечится он чтением словаря (PLAN D1, E4). */
class VocabularyResolverTest {

    /** Снимок в памяти: то, что на устройстве заменяет Room. */
    private class InMemoryStore(units: List<QuantityUnit>, forms: List<DosageForm>) : VocabularyStore {
        val units = units.associateBy { it.id }.toMutableMap()
        val forms = forms.associateBy { it.id }.toMutableMap()
        override suspend fun snapshot() = Vocabulary(units.values, forms.values)
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) {
            this.units += units.associateBy { it.id }
            this.forms += forms.associateBy { it.id }
        }
    }

    private val requests = mutableListOf<String>()

    private val store = InMemoryStore(listOf(TABLETS), listOf(TABLET_FORM))

    private fun resolver(online: Boolean) = VocabularyResolver(
        store,
        MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    requests += request.url.encodedPath
                    if (!online) throw IOException("связи нет")
                    val body = when (request.url.encodedPath) {
                        "/v1/quantity-units" -> """[{"id":"${TABLETS.id}","name":"${TABLETS.name}"},
                            {"id":"${MILLILITRES.id}","name":"${MILLILITRES.name}"}]"""
                        "/v1/form-types" -> """[{"id":"${TABLET_FORM.id}","name":"${TABLET_FORM.name}"}]"""
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
    )

    @Test
    fun aHitNeedsNoNetwork() = runTest {
        val found = resolver(online = false).resolve { it.unitOrMiss(TABLETS.id) }
        assertEquals(VocabularyResolver.Resolution.Resolved(TABLETS), found)
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun aMissReadsTheVocabularyAndRetries() = runTest {
        // Миллилитров в снимке нет: словарь дочитывается, и тот же разбор проходит.
        val found = resolver(online = true).resolve { it.unitOrMiss(MILLILITRES.id) }
        assertEquals(VocabularyResolver.Resolution.Resolved(MILLILITRES), found)
        assertEquals(listOf("/v1/quantity-units", "/v1/form-types"), requests)
        assertEquals(MILLILITRES, store.snapshot().unit(MILLILITRES.id))
    }

    @Test
    fun aMissWithoutConnectionStaysAMissWithItsReason() = runTest {
        val found = resolver(online = false).resolve { it.unitOrMiss(MILLILITRES.id) }
        assertTrue(found is VocabularyResolver.Resolution.Unresolved)
        found as VocabularyResolver.Resolution.Unresolved
        assertEquals(VocabularyMiss.Kind.UNIT, found.miss.kind)
        assertEquals(MILLILITRES.id, found.miss.id)
        assertEquals(ApiFailure.Unavailable, found.failure)
    }

    @Test
    fun aMissTheServerDoesNotKnowEitherIsNotADelay() = runTest {
        val unknown = DosageForm(kotlin.uuid.Uuid.parse("00000000-0000-4000-8000-0000000000ff"), "порошок")
        val found = resolver(online = true).resolve { it.formOrMiss(unknown.id) }
        assertEquals(
            VocabularyResolver.Resolution.Unresolved(VocabularyMiss(VocabularyMiss.Kind.FORM, unknown.id), failure = null)
                .miss.id,
            (found as VocabularyResolver.Resolution.Unresolved).miss.id
        )
        assertEquals(null, found.failure)
    }

    /**
     * Заход разбора многих записей дочитывает словарь один раз: вторая запись с тем же промахом не
     * идёт на сервер ни когда связи нет, ни когда словарь уже свежий и записи в нём просто нет.
     */
    @Test
    fun aSessionReadsTheVocabularyOnceWhateverMisses() = runTest {
        val offline = resolver(online = false).session()
        offline.resolve { it.unitOrMiss(MILLILITRES.id) }
        // Чтения HTTP-слой повторяет сам: считается не число попыток, а то, что второй промах не добавил ни одной.
        val afterFirst = requests.size
        offline.resolve { it.unitOrMiss(MILLILITRES.id) }
        assertEquals(afterFirst, requests.size)

        requests.clear()
        val unknown = kotlin.uuid.Uuid.random()
        val online = resolver(online = true).session()
        val first = online.resolve { it.unitOrMiss(unknown) }
        val second = online.resolve { it.unitOrMiss(unknown) }
        val known = online.resolve { it.unitOrMiss(MILLILITRES.id) }
        assertEquals(listOf("/v1/quantity-units", "/v1/form-types"), requests)
        assertTrue(first is VocabularyResolver.Resolution.Unresolved && second is VocabularyResolver.Resolution.Unresolved)
        assertEquals(VocabularyResolver.Resolution.Resolved(MILLILITRES), known)
    }
}
