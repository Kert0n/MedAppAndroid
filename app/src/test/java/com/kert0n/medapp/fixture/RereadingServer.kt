package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Rereading
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.SnapshotStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred

/**
 * Сервер для перечитывания одной вещи: общая полка [SHARED_KIT] и её коробки. Помнит, о чём его
 * спросили, и умеет **придержать ответ** ([hold]) — чтобы проверка увидела экран, ждущий ответа.
 * Что легло, он не кладёт в базу, а помнит ([laid]): как ответ ложится, проверяет укладка снимка.
 */
class RereadingServer(clock: Clock) {

    /** Пути, о которых спросили, по порядку. */
    val asked: MutableList<String> = CopyOnWriteArrayList()

    val laid: MutableList<ServerSnapshot> = CopyOnWriteArrayList()

    /** Коробки общей полки, которые сервер называет; остальные пути отвечают 404. */
    var boxes: Set<Uuid> = setOf(PACK)

    /** `false` — связь рвётся на самом запросе. */
    @Volatile
    var reachable: Boolean = true

    private var gate: CompletableDeferred<Unit>? = null

    /** Следующие ответы ждут [release]. */
    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    private fun drug(id: Uuid) = """
        {"drug":{"id":"$id","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"${TABLETS.id}",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$SHARED_KIT","version":4},
         "reservations":{"total":"4.000000","version":2}}
    """

    private val api = MedAppApi(
        medAppHttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += path
                gate?.await()
                if (!reachable) throw java.io.IOException("нет связи")
                val body = when {
                    path == "/v1/med-kits/$SHARED_KIT" ->
                        """{"id":"$SHARED_KIT","userCount":2,"drugs":[${boxes.joinToString(",") { drug(it) }}]}"""
                    path.startsWith("/v1/drugs/") -> Uuid.parse(path.removePrefix("/v1/drugs/")).takeIf { it in boxes }?.let(::drug)
                    else -> null
                } ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "https://medapp.test",
            retryDelay = { delayMillis(false) { 0L } }
        )
    )

    private object Words : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    private object Shelves : QueueStorage {
        override suspend fun medKit(id: Uuid): MedKitRef? =
            if (id == SHARED_KIT) medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref else null
        override fun changes() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override suspend fun nextDueAt(now: Instant): Instant? = null
        override suspend fun ready(now: Instant) = error("не для перечитывания")
        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant) = error("не для перечитывания")
        override suspend fun answered(id: Uuid, answer: RawResponse, at: Instant) = error("не для перечитывания")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для перечитывания")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для перечитывания")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для перечитывания")
    }

    private val storage = object : SnapshotStorage {
        override suspend fun serverKnows() = ServerKnowledge(setOf(SHARED_KIT), boxes, setOf(SHARED_KIT), boxes)
        override suspend fun packagesKnownOn(medKitId: Uuid): Set<Uuid> = boxes
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) {
            laid += snapshot
        }
    }

    private val vocabulary = VocabularyResolver(Words, api)

    val rereading = Rereading(api, storage, PackageSnapshotResolver(vocabulary, Shelves), vocabulary, clock)
}
