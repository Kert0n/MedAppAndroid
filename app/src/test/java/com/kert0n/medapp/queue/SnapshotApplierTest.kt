package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полный снимок — единственный способ узнать правду об общей полке: дешёвой сверки не существует
 * (PLAN B6, E4). Здесь проверяется целое: что прочитали, что из этого разрешилось и что легло
 * одной записью.
 */
class SnapshotApplierTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    private fun drug(id: Uuid, medKitId: Uuid, unitId: Uuid = TABLETS.id, amount: String = "17.000000") = """
        {"drug":{"id":"$id","name":"Парацетамол","quantity":"$amount","quantityUnitId":"$unitId",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$medKitId","version":4},
         "reservations":{"total":"4.000000","mine":"4.000000","version":2}}
    """

    private fun snapshotJson(vararg drugs: String, participants: Long = 2) = """
        {"id":"${Uuid.random()}","medKits":[
          {"id":"$HOME_KIT","userCount":$participants,"drugs":[${drugs.joinToString(",")}]}
        ]}
    """

    private class Store : VocabularyStore {
        var words = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun snapshot() = words
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) {
            words = Vocabulary(listOf(TABLETS) + units, listOf(TABLET_FORM) + forms)
        }
    }

    /** Знает только домашнюю полку: чужую снимок назвать может, а положить на неё некуда. */
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
        var calls = 0
        var participants: Map<Uuid, Long> = emptyMap()
        var packages: List<PackageSnapshot> = emptyList()
        override suspend fun lay(participants: Map<Uuid, Long>, packages: List<PackageSnapshot>, at: Instant) {
            calls++
            this.participants = participants
            this.packages = packages
        }
    }

    /** Сервер: снимок по `/users/me`, словарь по своим путям; `online = false` — связи нет вовсе. */
    private fun applier(storage: Laid, snapshot: String?, online: Boolean = true): SnapshotApplier {
        var units = 0
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    if (!online) throw java.io.IOException("нет связи")
                    val path = request.url.encodedPath
                    val body = when {
                        path.endsWith("/users/me") -> snapshot ?: throw java.io.IOException("нет связи")
                        path.endsWith("/quantity-units") -> {
                            units++
                            """[{"id":"${MILLILITRES.id}","name":"${MILLILITRES.name}"}]"""
                        }
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val vocabulary = VocabularyResolver(Store(), api)
        return SnapshotApplier(api, storage, vocabulary, PackageSnapshotResolver(vocabulary, Shelves()), Clock.fixed(now, ZoneOffset.UTC))
    }

    /** Снимок кладётся целиком и одной записью: и участники полки, и её коробки. */
    @Test
    fun theWholeSnapshotGoesDownInOneWrite() = runTest {
        val storage = Laid()

        val outcome = applier(storage, snapshotJson(drug(PACK, HOME_KIT), drug(OTHER_PACK, HOME_KIT))).refresh()

        assertEquals(SnapshotApplier.Outcome.Applied(medKits = 1, packages = 2, skipped = emptyList()), outcome)
        assertEquals(1, storage.calls)
        assertEquals(mapOf(HOME_KIT to 2L), storage.participants)
        assertEquals(listOf(PACK, OTHER_PACK), storage.packages.map { it.pack.id })
    }

    /**
     * Полка снимка нам неизвестна: коробку некуда класть, и выдумывать полку без имени и места
     * хранения мы не беремся (PLAN C0). Пропуск назван, остальное ложится.
     */
    @Test
    fun aBoxOnAnUnknownShelfIsSkippedByNameAndTheRestGoesDown() = runTest {
        val storage = Laid()

        val outcome = applier(storage, snapshotJson(drug(PACK, HOME_KIT), drug(OTHER_PACK, SHARED_KIT))).refresh()

        val applied = outcome as SnapshotApplier.Outcome.Applied
        assertEquals(listOf(PACK), storage.packages.map { it.pack.id })
        assertEquals(1, applied.skipped.size)
        assertTrue(applied.skipped.single(), applied.skipped.single().contains(SHARED_KIT.toString()))
    }

    /** Единица, появившаяся на сервере, — обычное дело: словарь дочитывается, и коробка ложится. */
    @Test
    fun aUnitThatAppearedOnTheServerIsReadAndTheBoxGoesDown() = runTest {
        val storage = Laid()

        val outcome = applier(storage, snapshotJson(drug(PACK, HOME_KIT, unitId = MILLILITRES.id))).refresh()

        assertEquals(SnapshotApplier.Outcome.Applied(medKits = 1, packages = 1, skipped = emptyList()), outcome)
        assertEquals(MILLILITRES, storage.packages.single().pack.quantity.unit)
    }

    /** Не прочитали — не кладём ничего: кэш остаётся прежним, а причина названа (PLAN E4). */
    @Test
    fun aSnapshotThatCouldNotBeReadChangesNothing() = runTest {
        val storage = Laid()

        val outcome = applier(storage, snapshot = null, online = false).refresh()

        assertEquals(SnapshotApplier.Outcome.Refused(Unavailability.NO_CONNECTION), outcome)
        assertEquals(0, storage.calls)
    }
}
