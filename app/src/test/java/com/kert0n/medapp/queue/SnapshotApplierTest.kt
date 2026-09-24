package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyStore
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.register.MedAppRegister
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.pack.PackageSnapshot
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

    private fun snapshotJson(vararg drugs: String, participants: Long = 2, also: String = "") = """
        {"id":"${Uuid.random()}","medKits":[
          {"id":"$HOME_KIT","userCount":$participants,"drugs":[${drugs.joinToString(",")}]}$also
        ]}
    """

    private fun shelfJson(id: Uuid, vararg drugs: String) =
        """,{"id":"$id","userCount":3,"drugs":[${drugs.joinToString(",")}]}"""

    private fun knowledge(
        medKits: Set<Uuid> = emptySet(),
        packages: Set<Uuid> = emptySet(),
        heldMedKits: Set<Uuid> = medKits + HOME_KIT,
        heldPackages: Set<Uuid> = packages
    ) = ServerKnowledge(medKits, packages, heldMedKits, heldPackages)

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
        override suspend fun operation(id: Uuid): SyncOperation? = error("не для этого теста")
        override suspend fun knownPackage(id: Uuid): PackageSnapshot? = error("не для этого теста")
        override suspend fun layDown(snapshot: PackageSnapshot, at: Instant) = error("не для этого теста")
        override suspend fun write(operation: SyncOperation, was: SyncOperationStatus) = error("не для этого теста")
        override suspend fun unclosedOfMedKit(medKitId: Uuid): List<StoredSyncOperation> = error("не для этого теста")
        override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = error("не для этого теста")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для этого теста")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для этого теста")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для этого теста")
    }

    private class Laid(private val knew: ServerKnowledge) : SnapshotStorage {
        var calls = 0
        var laid: ServerSnapshot? = null
        val snapshot get() = requireNotNull(laid) { "снимок не клали" }
        override suspend fun serverKnows(): ServerKnowledge = knew
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) {
            calls++
            laid = snapshot
        }
    }

    /** Сервер: снимок по `/users/me`, словарь по своим путям; `online = false` — связи нет вовсе. */
    private var units = 0

    private fun applier(
        storage: Laid,
        snapshot: String?,
        online: Boolean = true,
        joined: Pair<HttpStatusCode, String>? = null
    ): SnapshotApplier {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    if (!online) throw java.io.IOException("нет связи")
                    val path = request.url.encodedPath
                    if (path.endsWith("/med-kit-memberships")) {
                        val (status, body) = requireNotNull(joined) { "вступления в этом тесте нет" }
                        return@MockEngine respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
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
        return SnapshotApplier(MedAppRegister(api, PackageSnapshotResolver(vocabulary, Shelves()), Clock.fixed(now, ZoneOffset.UTC)), storage, Clock.fixed(now, ZoneOffset.UTC))
    }

    /** Снимок кладётся целиком и одной записью: и участники полки, и её коробки. */
    @Test
    fun theWholeSnapshotGoesDownInOneWrite() = runTest {
        val storage = Laid(knowledge())

        val outcome = applier(storage, snapshotJson(drug(PACK, HOME_KIT), drug(OTHER_PACK, HOME_KIT))).refresh()

        assertEquals(SnapshotApplier.Outcome.Applied(medKits = 1, packages = 2, skipped = emptyList()), outcome)
        assertEquals(1, storage.calls)
        assertEquals(mapOf(HOME_KIT to 2L), storage.snapshot.participants)
        assertEquals(listOf(PACK, OTHER_PACK), storage.snapshot.packages.map { it.pack.id })
    }

    /**
     * Полки, которой у нас нет, в снимке — штатный случай: нас позвали, а ответ на вступление
     * потерялся. Снимок её приносит вместе с коробками (PLAN C0, E4).
     */
    @Test
    fun aShelfWeDoNotHaveArrivesWithItsBoxes() = runTest {
        val storage = Laid(knowledge())

        val outcome = applier(
            storage,
            snapshotJson(drug(PACK, HOME_KIT), also = shelfJson(SHARED_KIT, drug(OTHER_PACK, SHARED_KIT)))
        ).refresh()

        assertEquals(SnapshotApplier.Outcome.Applied(medKits = 2, packages = 2, skipped = emptyList()), outcome)
        assertEquals(setOf(SHARED_KIT), storage.snapshot.arrivedMedKits)
        assertEquals(SHARED_KIT, storage.snapshot.packages.single { it.pack.id == OTHER_PACK }.pack.medKit.id)
    }

    /**
     * Полка была у нас к началу чтения, а к разбору её уже нет — убрали, пока снимок летел.
     * Запоздавший снимок её не возвращает: пропуск назван, остальное ложится (PLAN C0).
     */
    @Test
    fun aShelfRemovedWhileTheSnapshotFlewDoesNotComeBack() = runTest {
        val storage = Laid(knowledge(heldMedKits = setOf(HOME_KIT, SHARED_KIT)))

        val outcome = applier(
            storage,
            snapshotJson(drug(PACK, HOME_KIT), also = shelfJson(SHARED_KIT, drug(OTHER_PACK, SHARED_KIT)))
        ).refresh()

        val applied = outcome as SnapshotApplier.Outcome.Applied
        assertEquals(emptySet<Uuid>(), storage.snapshot.arrivedMedKits)
        assertEquals(listOf(PACK), storage.snapshot.packages.map { it.pack.id })
        assertTrue(applied.skipped.single(), applied.skipped.single().contains(SHARED_KIT.toString()))
    }

    /** Единица, появившаяся на сервере, — обычное дело: словарь дочитывается, и коробка ложится. */
    @Test
    fun aUnitThatAppearedOnTheServerIsReadAndTheBoxGoesDown() = runTest {
        val storage = Laid(knowledge())

        val outcome = applier(storage, snapshotJson(drug(PACK, HOME_KIT, unitId = MILLILITRES.id))).refresh()

        assertEquals(SnapshotApplier.Outcome.Applied(medKits = 1, packages = 1, skipped = emptyList()), outcome)
        assertEquals(MILLILITRES, storage.snapshot.packages.single().pack.quantity.unit)
    }

    /**
     * Чего в снимке нет, к тому доступа больше нет: полка, которую сервер знал, а теперь не
     * называет, и коробка, которую он не назвал нигде (PLAN E4).
     */
    @Test
    fun whatTheSnapshotDoesNotNameIsGone() = runTest {
        val gone = Uuid.random()
        val storage = Laid(knowledge(medKits = setOf(HOME_KIT, gone), packages = setOf(PACK, OTHER_PACK)))

        applier(storage, snapshotJson(drug(PACK, HOME_KIT))).refresh()

        assertEquals(setOf(gone), storage.snapshot.goneMedKits)
        assertEquals(setOf(OTHER_PACK), storage.snapshot.gonePackages)
    }

    /**
     * Пропажа считается по названным номерам, а не по разрешённым: коробку, которую сервер назвал,
     * а мы не смогли разрешить, мы не видим — но она не пропала, и кончать её нечем.
     */
    @Test
    fun aBoxNamedButUnresolvedIsNotCountedAsGone() = runTest {
        val storage = Laid(knowledge(medKits = setOf(HOME_KIT), packages = setOf(PACK, OTHER_PACK)))

        applier(storage, snapshotJson(drug(PACK, HOME_KIT), drug(OTHER_PACK, HOME_KIT, unitId = Uuid.random()))).refresh()

        assertEquals(emptySet<Uuid>(), storage.snapshot.gonePackages)
        assertEquals(listOf(PACK), storage.snapshot.packages.map { it.pack.id })
    }

    /** Не прочитали — не кладём ничего: кэш остаётся прежним, а причина названа (PLAN E4). */
    @Test
    fun aSnapshotThatCouldNotBeReadChangesNothing() = runTest {
        val storage = Laid(knowledge())

        val outcome = applier(storage, snapshot = null, online = false).refresh()

        assertEquals(SnapshotApplier.Outcome.Refused(Unavailability.NO_CONNECTION), outcome)
        assertEquals(0, storage.calls)
    }

    /**
     * Вступление — тот же снимок, только одной полки: сервер отвечает полкой с содержимым, и она
     * ложится одной записью, заводясь у нас. Утверждения о целом в нём нет — пропажи оно не
     * объявляет (PLAN C0, E4).
     */
    @Test
    fun joiningLaysTheShelfWithItsBoxesAndDeclaresNothingGone() = runTest {
        val storage = Laid(knowledge(medKits = setOf(HOME_KIT), packages = setOf(PACK)))
        val shelf = """{"id":"$SHARED_KIT","userCount":3,"drugs":[${drug(OTHER_PACK, SHARED_KIT)}]}"""

        val joining = applier(storage, snapshot = null, joined = HttpStatusCode.Created to shelf)
            .join(InvitationKey("ключ"))

        assertEquals(SnapshotApplier.Joining.Joined(SHARED_KIT), joining)
        assertEquals(mapOf(SHARED_KIT to 3L), storage.snapshot.participants)
        assertEquals(setOf(SHARED_KIT), storage.snapshot.arrivedMedKits)
        assertEquals(listOf(OTHER_PACK), storage.snapshot.packages.map { it.pack.id })
        assertEquals(emptySet<Uuid>(), storage.snapshot.goneMedKits)
        assertEquals(emptySet<Uuid>(), storage.snapshot.gonePackages)
    }

    /** «Уже вступили» и «код недействителен» — ответы, а не снимок: класть нечего. */
    @Test
    fun joiningRefusalsLayNothing() = runTest {
        val storage = Laid(knowledge())

        val already = applier(storage, snapshot = null, joined = HttpStatusCode.Conflict to "").join(InvitationKey("ключ"))
        val invalid = applier(storage, snapshot = null, joined = HttpStatusCode.NotFound to "").join(InvitationKey("ключ"))
        val unknown = applier(storage, snapshot = null, joined = HttpStatusCode.BadGateway to "").join(InvitationKey("ключ"))

        assertEquals(SnapshotApplier.Joining.AlreadyMember, already)
        assertEquals(SnapshotApplier.Joining.InvitationInvalid, invalid)
        assertEquals(SnapshotApplier.Joining.OutcomeUnknown, unknown)
        assertEquals(0, storage.calls)
    }

    /**
     * Две коробки называют единицу, которой нет и на сервере: словарь дочитывается один раз на всё
     * чтение, а не на каждую коробку, и обе названы пропуском.
     */
    @Test
    fun theVocabularyIsReadOnceForTheWholeSnapshot() = runTest {
        val storage = Laid(knowledge())
        val unknown = Uuid.random()

        val outcome = applier(
            storage,
            snapshotJson(drug(PACK, HOME_KIT, unitId = unknown), drug(OTHER_PACK, HOME_KIT, unitId = unknown))
        ).refresh() as SnapshotApplier.Outcome.Applied

        assertEquals(1, units)
        assertEquals(2, outcome.skipped.size)
    }
}
