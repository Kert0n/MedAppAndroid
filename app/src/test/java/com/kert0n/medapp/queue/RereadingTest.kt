package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
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
 * Перечитывание одной вещи (PLAN E4): что оно спрашивает, что кладёт и о чём **не** говорит. Как
 * уложенное ложится в базу — по версиям, мимо коробки в полёте, — отвечает укладка снимка и её
 * проверки; здесь — утверждение, которое перечитывание ей передаёт.
 */
class RereadingTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    /** Коробка на общей полке, которой у нас нет вовсе: её назвали впервые. */
    private val newcomer: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000029")

    private fun drug(id: Uuid, medKitId: Uuid = SHARED_KIT, unitId: Uuid = TABLETS.id) = """
        {"drug":{"id":"$id","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"$unitId",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$medKitId","version":4},
         "reservations":{"total":"4.000000","version":2}}
    """

    private fun shelf(vararg drugs: String) =
        """{"id":"$SHARED_KIT","userCount":3,"drugs":[${drugs.joinToString(",")}]}"""

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

    /** Хранение: [onShelf] — коробки общей полки, о которых сервер знает; [held] — все живые у нас. */
    private class Laid(private val onShelf: Set<Uuid>, private val held: Set<Uuid>) : SnapshotStorage {
        var laid: ServerSnapshot? = null
        override suspend fun serverKnows() = ServerKnowledge(setOf(SHARED_KIT), onShelf, setOf(HOME_KIT, SHARED_KIT), held)
        override suspend fun packagesKnownOn(medKitId: Uuid): Set<Uuid> = if (medKitId == SHARED_KIT) onShelf else emptySet()
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
        return Rereading(api, storage, PackageSnapshotResolver(vocabulary, Shelves()), vocabulary, Clock.fixed(now, ZoneOffset.UTC))
    }

    /** Полка называет своё содержимое и участников, и прочитанными считаются названные коробки. */
    @Test
    fun aShelfLaysItsContentsAndNamesWhatItRead() = runTest {
        val storage = Laid(onShelf = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/med-kits/$SHARED_KIT" to shelf(drug(PACK), drug(newcomer)))).medKit(SHARED_KIT)

        assertEquals(Rereading.Outcome.Read(packages = setOf(PACK, newcomer)), outcome)
        val laid = requireNotNull(storage.laid)
        assertEquals(mapOf(SHARED_KIT to 3L), laid.participants)
        assertEquals(setOf(PACK, newcomer), laid.packages.mapTo(HashSet()) { it.pack.id })
        assertEquals(emptySet<Uuid>(), laid.gonePackages)
    }

    /**
     * Коробка, которой полка не назвала, у нас кончается: полка утверждает о себе целиком. Про
     * чужие полки не сказано ничего — пропавшими названы только коробки **этой** полки.
     */
    @Test
    fun aBoxTheShelfDidNotNameEndsAndNothingElseDoes() = runTest {
        val storage = Laid(onShelf = setOf(PACK, OTHER_PACK), held = setOf(PACK, OTHER_PACK, newcomer))

        rereading(storage, mapOf("/v1/med-kits/$SHARED_KIT" to shelf(drug(PACK)))).medKit(SHARED_KIT)

        val laid = requireNotNull(storage.laid)
        assertEquals(setOf(OTHER_PACK), laid.gonePackages)
        assertEquals(emptySet<Uuid>(), laid.goneMedKits)
    }

    /** Коробку, которую не удалось разрешить, сервер назвал: она не пропала и не прочитана. */
    @Test
    fun aBoxThatCouldNotBeResolvedIsNeitherGoneNorRead() = runTest {
        val storage = Laid(onShelf = setOf(PACK, OTHER_PACK), held = setOf(PACK, OTHER_PACK))

        val outcome = rereading(storage, mapOf("/v1/med-kits/$SHARED_KIT" to shelf(drug(PACK), drug(OTHER_PACK, medKitId = HOME_KIT))))
            .medKit(SHARED_KIT)

        assertEquals(Rereading.Outcome.Read(packages = setOf(PACK)), outcome)
        assertEquals(emptySet<Uuid>(), requireNotNull(storage.laid).gonePackages)
    }

    /** Полки у нас больше нет — её убрали у всех или нас вывели: это записано, как в снимке. */
    @Test
    fun aShelfTheServerNoLongerHasIsGone() = runTest {
        val storage = Laid(onShelf = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/med-kits/$SHARED_KIT" to null)).medKit(SHARED_KIT)

        assertEquals(Rereading.Outcome.Gone, outcome)
        assertEquals(setOf(SHARED_KIT), requireNotNull(storage.laid).goneMedKits)
    }

    /** Коробка кладётся одна и называет себя прочитанной. */
    @Test
    fun aBoxLaysItselfAlone() = runTest {
        val storage = Laid(onShelf = setOf(PACK, OTHER_PACK), held = setOf(PACK, OTHER_PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to drug(PACK))).pack(PACK)

        assertEquals(Rereading.Outcome.Read(packages = setOf(PACK)), outcome)
        val laid = requireNotNull(storage.laid)
        assertEquals(listOf(PACK), laid.packages.map { it.pack.id })
        assertEquals(emptySet<Uuid>(), laid.gonePackages)
        assertEquals(emptyMap<Uuid, Long>(), laid.participants)
    }

    /** Коробки больше нет — допили, выбросили или унесли туда, где нас нет. */
    @Test
    fun aBoxTheServerNoLongerHasIsGone() = runTest {
        val storage = Laid(onShelf = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to null)).pack(PACK)

        assertEquals(Rereading.Outcome.Gone, outcome)
        assertEquals(setOf(PACK), requireNotNull(storage.laid).gonePackages)
    }

    /** Коробка переехала на полку, которой у нас нет: это не «пропала», и не сказано ничего. */
    @Test
    fun aBoxMovedWhereWeAreNotSaysNothing() = runTest {
        val storage = Laid(onShelf = setOf(PACK), held = setOf(PACK))

        val outcome = rereading(storage, mapOf("/v1/drugs/$PACK" to drug(PACK, medKitId = HOME_KIT))).pack(PACK)

        assertEquals(Rereading.Outcome.Read(packages = emptySet()), outcome)
        assertNull(storage.laid)
    }

    /** Связи нет — ничего не легло, и причина названа. */
    @Test
    fun withoutConnectionNothingIsLaid() = runTest {
        val storage = Laid(onShelf = setOf(PACK), held = setOf(PACK))

        val shelfOutcome = rereading(storage, emptyMap(), online = false).medKit(SHARED_KIT)
        val boxOutcome = rereading(storage, emptyMap(), online = false).pack(PACK)

        assertEquals(Rereading.Outcome.Refused(Unavailability.NO_CONNECTION), shelfOutcome)
        assertEquals(Rereading.Outcome.Refused(Unavailability.NO_CONNECTION), boxOutcome)
        assertNull(storage.laid)
    }
}
