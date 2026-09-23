package com.kert0n.medapp.network.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Receipt
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.pack.PackageSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Снимок называет чужое — единицу, форму, аптечку, — и разрешается одним исходом: либо он собран
 * в домен, либо названо, что неизвестно и ждать ли (PLAN E3, E4). Исключений наружу нет.
 */
class PackageSnapshotResolverTest {

    private val snapshotJson = """
        {"drug":{"id":"$PACK","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"${TABLETS.id}",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$HOME_KIT","version":4},
         "reservations":{"total":"4.000000","mine":"4.000000","version":2}}
    """

    private fun dto(json: String = snapshotJson): PackageSnapshotNetworkDTO =
        medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), json)

    private class Store : VocabularyStore {
        var words = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun snapshot() = words
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) {
            words = Vocabulary(listOf(TABLETS) + units, listOf(TABLET_FORM) + forms)
        }
    }

    /** Хранилище, у которого есть только домашняя аптечка; остальное работнику здесь не нужно. */
    private class Storage : QueueStorage {
        override suspend fun medKit(id: Uuid): MedKitRef? =
            if (id == HOME_KIT) medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED).ref else null
        override fun changes(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun nextDueAt(now: Instant): Instant? = null
        override suspend fun ready(now: Instant) = error("не для этого теста")
        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant) = error("не для этого теста")
        override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = error("не для этого теста")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для этого теста")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для этого теста")
        override suspend fun enqueue(queued: QueuedCommand, shelf: kotlin.uuid.Uuid, at: Instant) = error("не для этого теста")
    }

    private fun resolver(online: Boolean): PackageSnapshotResolver {
        val vocabulary = VocabularyResolver(
            Store(),
            MedAppApi(
                medAppHttpClient(
                    MockEngine { request ->
                        if (!online) throw java.io.IOException("нет связи")
                        val body = if (request.url.encodedPath.endsWith("/quantity-units")) {
                            """[{"id":"${MILLILITRES.id}","name":"${MILLILITRES.name}"}]"""
                        } else {
                            "[]"
                        }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                    "https://medapp.test",
                    retryDelay = { delayMillis(false) { 0L } }
                )
            )
        )
        return PackageSnapshotResolver(vocabulary, Storage())
    }

    @Test
    fun aSnapshotNamingOnlyKnownThingsIsResolved() = runTest {
        val resolution = resolver(online = false).resolve(dto(), EARLIER)
        val resolved = resolution as PackageSnapshotResolver.Resolution.Resolved
        assertEquals(PACK, resolved.snapshot.pack.id)
        assertEquals(HOME_KIT, resolved.snapshot.pack.medKit.id)
        assertEquals(4L, resolved.snapshot.sync.version?.number)
    }

    /**
     * Полка, которой у нас нет, — не «ещё не дочитали», а «коробка ушла туда, где нас нет»: ответ
     * окончательный, и словарь ради него не читается (PLAN E3, E6).
     */
    @Test
    fun anUnknownMedKitMeansTheBoxIsElsewhereForGood() = runTest {
        val resolution = resolver(online = true).resolve(dto(snapshotJson.replace(HOME_KIT.toString(), SHARED_KIT.toString())), EARLIER)
        assertEquals(PackageSnapshotResolver.Resolution.Elsewhere(SHARED_KIT), resolution)
    }

    /** Ту же полку полный снимок приносит сам: коробке на ней есть куда лечь (PLAN E4). */
    @Test
    fun aMedKitTheSnapshotBringsResolvesTheBoxOntoIt() = runTest {
        val resolution = resolver(online = false).resolve(
            dto(snapshotJson.replace(HOME_KIT.toString(), SHARED_KIT.toString())), EARLIER, arriving = setOf(SHARED_KIT)
        )
        val resolved = resolution as PackageSnapshotResolver.Resolution.Resolved
        assertEquals(SHARED_KIT, resolved.snapshot.pack.medKit.id)
        assertEquals(MedKit.Publication.PUBLISHED, resolved.snapshot.pack.medKit.publication)
    }

    @Test
    fun anUnknownUnitIsReadFromTheServerAndResolved() = runTest {
        val resolution = resolver(online = true).resolve(dto(snapshotJson.replace(TABLETS.id.toString(), MILLILITRES.id.toString())), EARLIER)
        assertTrue(resolution is PackageSnapshotResolver.Resolution.Resolved)
    }

    @Test
    fun anUnknownUnitWithoutConnectionIsUnresolvedAndStopsThePass() = runTest {
        val resolution = resolver(online = false).resolve(dto(snapshotJson.replace(TABLETS.id.toString(), MILLILITRES.id.toString())), EARLIER)
        val unresolved = resolution as PackageSnapshotResolver.Resolution.Unresolved
        assertTrue(unresolved.stop)
    }

    /** Снимок вне контракта — данные сервера, а не ошибка программиста: он ждёт с причиной, а не роняет проход. */
    @Test
    fun aSnapshotBreakingADomainRuleIsUnresolvedNotThrown() = runTest {
        val empty = snapshotJson.replace("\"quantity\":\"17.000000\"", "\"quantity\":\"0\"")
        val resolution = resolver(online = false).resolve(dto(empty), EARLIER)
        val unresolved = resolution as PackageSnapshotResolver.Resolution.Unresolved
        assertTrue(unresolved.reason, unresolved.reason.startsWith("снимок вне контракта"))
        assertFalse(unresolved.stop)
    }
}
