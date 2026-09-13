package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.HOME_KIT
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
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SnapshotStorage
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
import org.junit.Test

/**
 * Вступление требует связи, и ответ на него может потеряться: сервер вступил, а повтор тем же кодом
 * отвечает «уже вступили» без номера полки. Сценарий не оставляет человека без полки — полный
 * снимок приносит ту, которой у нас не было (PLAN C0, E4).
 */
class MedKitJoiningTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val key = InvitationKey("ключ")

    private fun shelfJson(id: Uuid) = """{"id":"$id","userCount":2,"drugs":[]}"""

    /** Сервер знает домашнюю полку; [plus] — полки, в которые мы, по его словам, тоже вступили. */
    private fun snapshotJson(vararg plus: Uuid) =
        """{"id":"${Uuid.random()}","medKits":[${(listOf(HOME_KIT) + plus).joinToString(",") { shelfJson(it) }}]}"""

    private class Store : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    private class Shelves : QueueStorage {
        override suspend fun medKit(id: Uuid): MedKitRef? =
            if (id == HOME_KIT) medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED).ref else null
        override fun changes() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override suspend fun nextDueAt(now: Instant): Instant? = null
        override suspend fun ready(now: Instant) = error("не для этого теста")
        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant) = error("не для этого теста")
        override suspend fun answered(id: Uuid, answer: RawResponse, at: Instant) = error("не для этого теста")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для этого теста")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для этого теста")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для этого теста")
    }

    private class Laid : SnapshotStorage {
        val laid = ArrayList<ServerSnapshot>()
        override suspend fun serverKnows() = ServerKnowledge(setOf(HOME_KIT), emptySet(), setOf(HOME_KIT), emptySet())
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) {
            laid += snapshot
        }
    }

    /** Сервер: ответ на вступление и снимок; `null` у снимка — связи нет. */
    private fun joining(storage: Laid, joined: Pair<HttpStatusCode, String>, snapshot: String?): MedKitJoining {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    val (status, body) = when {
                        path.endsWith("/med-kit-memberships") -> joined
                        path.endsWith("/users/me") -> HttpStatusCode.OK to (snapshot ?: throw java.io.IOException("нет связи"))
                        else -> HttpStatusCode.OK to "[]"
                    }
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val vocabulary = VocabularyResolver(Store(), api)
        val applier = SnapshotApplier(api, storage, vocabulary, PackageSnapshotResolver(vocabulary, Shelves()), Clock.fixed(now, ZoneOffset.UTC))
        return MedKitJoining(applier)
    }

    /** Обычный путь: вступили — полка легла одним ответом, снимок не нужен. */
    @Test
    fun joiningBringsTheShelfInOneAnswer() = runTest {
        val storage = Laid()

        val outcome = joining(storage, HttpStatusCode.Created to shelfJson(SHARED_KIT), snapshot = null).join(key)

        assertEquals(MedKitJoining.Outcome.Joined(SHARED_KIT), outcome)
        assertEquals(1, storage.laid.size)
    }

    /**
     * «Уже вступили», а полки у нас нет — прошлый ответ потерялся. Снимок приносит её, и это и есть
     * вступление: экран откроет полку, а не скажет «вы уже там» про полку, которой не видно.
     */
    @Test
    fun alreadyAMemberWithoutTheShelfRecoversItFromTheSnapshot() = runTest {
        val storage = Laid()

        val outcome = joining(storage, HttpStatusCode.Conflict to "", snapshotJson(SHARED_KIT)).join(key)

        assertEquals(MedKitJoining.Outcome.Joined(SHARED_KIT), outcome)
        assertEquals(setOf(SHARED_KIT), storage.laid.single().arrivedMedKits)
    }

    /** «Уже вступили», и полка давно у нас: снимок нового не принёс — так и говорим. */
    @Test
    fun alreadyAMemberWithTheShelfSaysSo() = runTest {
        val outcome = joining(Laid(), HttpStatusCode.Conflict to "", snapshotJson()).join(key)

        assertEquals(MedKitJoining.Outcome.AlreadyMember, outcome)
    }

    /** Ответ на вступление потерялся, а сервер вступил: снимок это показывает новой полкой. */
    @Test
    fun aLostAnswerThatJoinedIsFoundInTheSnapshot() = runTest {
        val outcome = joining(Laid(), HttpStatusCode.BadGateway to "", snapshotJson(SHARED_KIT)).join(key)

        assertEquals(MedKitJoining.Outcome.Joined(SHARED_KIT), outcome)
    }

    /** Ответ потерялся, и новой полки нет: не вступили — повторить позже. */
    @Test
    fun aLostAnswerThatDidNotJoinAsksToRetry() = runTest {
        val outcome = joining(Laid(), HttpStatusCode.BadGateway to "", snapshotJson()).join(key)

        assertEquals(MedKitJoining.Outcome.Unavailable(Unavailability.SERVER_SILENT), outcome)
    }

    /** Код неизвестен, истёк или пригласивший вышел — для нас это одно, и снимок не читается (B6). */
    @Test
    fun anInvalidInvitationIsOneAnswer() = runTest {
        val storage = Laid()

        val outcome = joining(storage, HttpStatusCode.NotFound to "", snapshot = null).join(key)

        assertEquals(MedKitJoining.Outcome.InvitationInvalid, outcome)
        assertEquals(0, storage.laid.size)
    }
}
