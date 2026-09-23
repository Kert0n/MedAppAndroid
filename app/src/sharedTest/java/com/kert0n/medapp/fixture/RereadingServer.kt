package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Receipt
import com.kert0n.medapp.queue.Rereading
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSnapshot
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
 * Сервер для перечитывания: список полок и коробки общей полки [SHARED_KIT]. Помнит, о чём его
 * спросили, и умеет **придержать ответ** ([hold]) — чтобы проверка увидела экран, ждущий ответа.
 * Что легло, он не кладёт в базу, а помнит ([laid]): как ответ ложится, проверяет укладка снимка.
 */
class RereadingServer(clock: Clock) {

    /** Пути, о которых спросили, по порядку. */
    val asked: MutableList<String> = CopyOnWriteArrayList()

    val laid: MutableList<ServerSnapshot> = CopyOnWriteArrayList()

    /** Коробки общей полки, которые сервер называет; остальные пути отвечают 404. */
    var boxes: Set<Uuid> = setOf(PACK)

    /** Полки, в которых мы есть, по словам сервера. */
    var shelves: Set<Uuid> = setOf(SHARED_KIT)

    /** Полки, которые у нас лежат как общие, — что «сервер знал» до запроса. */
    var ours: Set<Uuid> = setOf(SHARED_KIT)

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
                    path == "/v1/med-kits" ->
                        shelves.joinToString(",", "[", "]") { """{"id":"$it","userCount":2,"drugIds":[]}""" }
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
        override suspend fun operation(id: Uuid): SyncOperation? = error("не для перечитывания")
        override suspend fun knownPackage(id: Uuid): PackageSnapshot? = error("не для перечитывания")
        override suspend fun layDown(snapshot: PackageSnapshot, at: Instant) = error("не для перечитывания")
        override suspend fun write(operation: SyncOperation, was: SyncOperationStatus) = error("не для перечитывания")
        override suspend fun unclosedOfMedKit(medKitId: Uuid): List<StoredSyncOperation> = error("не для перечитывания")
        override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = error("не для перечитывания")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для перечитывания")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для перечитывания")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для перечитывания")
    }

    private val storage = object : SnapshotStorage {
        override suspend fun serverKnows() = ServerKnowledge(ours, boxes, ours, boxes)
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) {
            laid += snapshot
        }
    }

    private val vocabulary = VocabularyResolver(Words, api)

    val rereading = Rereading(api, storage, PackageSnapshotResolver(vocabulary, Shelves), clock)
}
