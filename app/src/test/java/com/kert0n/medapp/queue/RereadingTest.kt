package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Перечитывание (PLAN E4): что оно спрашивает, что кладёт и о чём **не** говорит. Как
 * уложенное ложится в базу — по версиям, мимо коробки в полёте, — отвечает укладка снимка и её
 * проверки; здесь — утверждение, которое перечитывание ей передаёт.
 */
class RereadingTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    /** Общая полка, в которой нас нет и которой у нас нет. */
    private val stranger: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000019")

    private fun drug(id: Uuid, medKitId: Uuid = SHARED_KIT, unitId: Uuid = TABLETS.id) = """
        {"drug":{"id":"$id","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"$unitId",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$medKitId","version":4},
         "reservations":{"total":"4.000000","version":2}}
    """

    private fun shelves(vararg ids: Uuid) =
        ids.joinToString(",", "[", "]") { """{"id":"$it","userCount":3,"drugIds":[]}""" }

    private class Store : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    /** Знает обе полки: общую, куда кладут, и домашнюю — чтобы чужая полка была чьей-то. */
    private class Shelves : QueueStorage {
        override suspend fun medKit(id: Uuid): MedKitRef? = when (id) {
            SHARED_KIT -> medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref
            else -> null
        }
        override fun changes() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override suspend fun nextDueAt(now: Instant): Instant? = null
        override suspend fun ready(now: Instant) = error("не для этого теста")
        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant) = error("не для этого теста")
        override suspend fun answered(id: Uuid, answer: RawResponse, at: Instant) = error("не для этого теста")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для этого теста")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для этого теста")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для этого теста")
    }

    /** Хранение: [known] — коробки, о которых сервер знает; [held] — все живые у нас. Полки: общая и домашняя. */
    private class Laid(private val known: Set<Uuid>, private val held: Set<Uuid>) : SnapshotStorage {
        var laid: ServerSnapshot? = null
        override suspend fun serverKnows() = ServerKnowledge(setOf(SHARED_KIT), known, setOf(HOME_KIT, SHARED_KIT), held)
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) {
            laid = snapshot
        }
    }

    /** Ответ на путь: тело с кодом `200`, `null` — `404`; `online = false` — связи нет вовсе. */
    private fun rereading(storage: Laid, answers: Map<String, String?>, online: Boolean = true): Rereading {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    if (!online) throw java.io.IOException("нет связи")
                    val path = request.url.encodedPath
                    if (path !in answers) error("перечитывание спросило лишнее: $path")
                    val body = answers[path] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val vocabulary = VocabularyResolver(Store(), api)
        return Rereading(api, storage, PackageSnapshotResolver(vocabulary, Shelves()), Clock.fixed(now, ZoneOffset.UTC))
    }

    /**
     * Список называет полки, в которых мы есть, и сколько в них людей. Ничьё содержимое он не
     * трогает, и полку, которой у нас нет, не приносит: без содержимого она была бы половиной полки.
     */
    @Test
    fun theListTellsParticipantsAndBringsNothingElse() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/med-kits" to shelves(SHARED_KIT, stranger))).medKits()

        assertEquals(Rereading.Outcome.Read, outcome)
        val laid = requireNotNull(storage.laid)
        assertEquals(mapOf(SHARED_KIT to 3L), laid.participants)
        assertEquals(emptyList<PackageSnapshot>(), laid.packages)
        assertEquals(emptySet<Uuid>(), laid.gonePackages)
        assertEquals(emptySet<Uuid>(), laid.arrivedMedKits)
        assertEquals(emptySet<Uuid>(), laid.goneMedKits)
    }

    /** Общей полки, которую сервер знал, в списке нет — нас из неё вывели или её убрали у всех. */
    @Test
    fun aSharedShelfTheListDoesNotNameIsGone() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        rereading(storage, mapOf("/v1/med-kits" to shelves(stranger))).medKits()

        assertEquals(setOf(SHARED_KIT), requireNotNull(storage.laid).goneMedKits)
    }

    /** Коробка кладётся одна и называет себя прочитанной. */
    @Test
    fun aBoxLaysItselfAlone() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to drug(PACK))).pack(PACK)

        assertEquals(Rereading.Outcome.Read, outcome)
        val laid = requireNotNull(storage.laid)
        assertEquals(listOf(PACK), laid.packages.map { it.pack.id })
        assertEquals(emptySet<Uuid>(), laid.gonePackages)
        assertEquals(emptyMap<Uuid, Long>(), laid.participants)
    }

    /** Коробки больше нет — допили, выбросили или унесли туда, где нас нет. */
    @Test
    fun aBoxTheServerNoLongerHasIsGone() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to null)).pack(PACK)

        assertEquals(Rereading.Outcome.Gone, outcome)
        assertEquals(setOf(PACK), requireNotNull(storage.laid).gonePackages)
    }

    /** Коробка переехала на полку, которой у нас нет: это не «пропала», и не сказано ничего. */
    @Test
    fun aBoxMovedWhereWeAreNotSaysNothing() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to drug(PACK, medKitId = HOME_KIT))).pack(PACK)

        assertEquals(Rereading.Outcome.Read, outcome)
        assertNull(storage.laid)
    }

    /** Связи нет — ничего не легло, и причина названа. */
    @Test
    fun withoutConnectionNothingIsLaid() = runTest {
        val storage = Laid(known = setOf(PACK), held = setOf(PACK))

        val listOutcome = rereading(storage, emptyMap(), online = false).medKits()
        val boxOutcome = rereading(storage, emptyMap(), online = false).pack(PACK)

        assertEquals(Rereading.Outcome.Refused(Unavailability.NO_CONNECTION), listOutcome)
        assertEquals(Rereading.Outcome.Refused(Unavailability.NO_CONNECTION), boxOutcome)
        assertNull(storage.laid)
    }
}
