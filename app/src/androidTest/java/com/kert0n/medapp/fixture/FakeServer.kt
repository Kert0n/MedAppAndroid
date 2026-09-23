package com.kert0n.medapp.fixture

import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.delivery.MedAppDoor
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.Synchronization
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.operation.QueueBacklogRoomStorage
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Сервер MedApp в памяти — ровно то, что нужно сквозным историям без боевого сервера (PLAN J2):
 * полки с коробками, остаток и брони с версиями, журнал `syncId` на сутки, потеря ответа после
 * применения и отказ по остатку. Настоящий сервер этого не позволяет попросить — упасть ровно
 * посередине или отказать по заказу, — а именно эти случаи и проверяются.
 *
 * Что здесь есть, взято с провода (B4, `WireContractTest`); чего нет — отвечает `204`.
 */
class FakeServer {

    class Drug(
        val id: Uuid,
        val medKitId: Uuid,
        var name: String,
        var quantity: BigDecimal,
        val unitId: Uuid,
        val formId: Uuid?,
        var version: Long = 1,
        var total: BigDecimal = BigDecimal.ZERO,
        var mine: BigDecimal? = null,
        var claimsVersion: Long = 1
    )

    val medKits = linkedMapOf<Uuid, Long>()
    val drugs = linkedMapOf<Uuid, Drug>()

    /** Ответ, который сервер применил, но клиент не получил: по одному разу на `syncId`. */
    val loseAnswerFor = mutableSetOf<Uuid>()

    /** Расход, который сервер отвергнет по остатку (`400`), — по коробке. */
    val refuseConsumptionOf = mutableSetOf<Uuid>()

    /** Связи нет: каждый запрос обрывается. */
    var offline = false

    val requests = mutableListOf<String>()

    private val journal = mutableMapOf<Uuid, String>()

    fun shelf(id: Uuid, participants: Long = 2) { medKits[id] = participants }

    fun drug(id: Uuid, medKitId: Uuid, name: String = "Парацетамол", quantity: String, unitId: Uuid = TABLETS.id, formId: Uuid? = TABLET_FORM.id, mine: String? = null, others: String = "0"): Drug {
        val drug = Drug(id, medKitId, name, BigDecimal(quantity), unitId, formId, mine = mine?.let { BigDecimal(it) })
        drug.total = BigDecimal(others) + (drug.mine ?: BigDecimal.ZERO)
        drugs[id] = drug
        return drug
    }

    private fun snapshot(drug: Drug): String = """
        {"drug":{"id":"${drug.id}","name":"${drug.name}","quantity":"${drug.quantity.toPlainString()}","quantityUnitId":"${drug.unitId}",
         ${drug.formId?.let { "\"formTypeId\":\"$it\"," } ?: ""}"medKitId":"${drug.medKitId}","version":${drug.version}},
         "reservations":{"total":"${drug.total.toPlainString()}",${drug.mine?.let { "\"mine\":\"${it.toPlainString()}\"," } ?: ""}"version":${drug.claimsVersion}}}
    """.trimIndent()

    private fun me(): String = """{"id":"${Uuid.random()}","medKits":[${medKits.entries.joinToString(",") { (id, count) ->
        """{"id":"$id","userCount":$count,"drugs":[${drugs.values.filter { it.medKitId == id }.joinToString(",") { snapshot(it) }}]}"""
    }}]}"""

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        body to status

    /** Ответ на запрос: пара «тело, статус»; `null` тела — `204`. */
    private fun answer(request: HttpRequestData): Pair<String?, HttpStatusCode> {
        val method = request.method.value
        val path = request.url.encodedPath
        requests += "$method $path"
        if (offline) throw IOException("связи нет")
        val body = (request.body as? TextContent)?.text.orEmpty()
        val sync = Regex("^/v1/drugs/([0-9a-f-]+)/sync/([0-9a-f-]+)$").find(path)
        val drug = Regex("^/v1/drugs/([0-9a-f-]+)$").find(path)
        val claim = Regex("^/v1/reservations/([0-9a-f-]+)$").find(path)
        return when {
            method == "GET" && path == "/v1/users/me" -> json(me())
            method == "GET" && drug != null -> drugs[Uuid.parse(drug.groupValues[1])]?.let { json(snapshot(it)) } ?: (null to HttpStatusCode.NotFound)
            method == "PUT" && sync != null -> {
                val target = drugs[Uuid.parse(sync.groupValues[1])] ?: return null to HttpStatusCode.NotFound
                val syncId = Uuid.parse(sync.groupValues[2])
                journal[syncId]?.let { return json(it) }
                if (target.id in refuseConsumptionOf) return null to HttpStatusCode.BadRequest
                val fields = Json.parseToJsonElement(body).jsonObject
                fields["consumed"]?.jsonPrimitive?.content?.let { consumed ->
                    val delta = BigDecimal(consumed)
                    if (delta > target.quantity) return null to HttpStatusCode.BadRequest
                    target.quantity = target.quantity - delta
                    target.version++
                }
                fields["reservation"]?.jsonObject?.get("amount")?.jsonPrimitive?.content?.let { amount ->
                    setMine(target, BigDecimal(amount))
                }
                val answered = snapshot(target)
                journal[syncId] = answered
                if (loseAnswerFor.remove(syncId)) throw IOException("ответ потерян после применения")
                json(answered)
            }
            method == "POST" && path == "/v1/reservations" -> {
                val fields = Json.parseToJsonElement(body).jsonObject
                val target = drugs[Uuid.parse(fields.getValue("drugId").jsonPrimitive.content)] ?: return null to HttpStatusCode.NotFound
                setMine(target, BigDecimal(fields.getValue("amount").jsonPrimitive.content))
                json("""{"drugId":"${target.id}","amount":"${target.mine!!.toPlainString()}"}""", HttpStatusCode.Created)
            }
            method == "PATCH" && claim != null -> {
                val target = drugs[Uuid.parse(claim.groupValues[1])] ?: return null to HttpStatusCode.NotFound
                val fields = Json.parseToJsonElement(body).jsonObject
                setMine(target, BigDecimal(fields.getValue("amount").jsonPrimitive.content))
                json("""{"drugId":"${target.id}","amount":"${target.mine!!.toPlainString()}"}""")
            }
            method == "DELETE" && claim != null -> {
                drugs[Uuid.parse(claim.groupValues[1])]?.let { setMine(it, null) }
                null to HttpStatusCode.NoContent
            }
            else -> null to HttpStatusCode.NoContent
        }
    }

    private fun setMine(drug: Drug, amount: BigDecimal?) {
        val others = drug.total - (drug.mine ?: BigDecimal.ZERO)
        drug.mine = amount
        drug.total = others + (amount ?: BigDecimal.ZERO)
        drug.claimsVersion++
    }

    val engine: MockEngine = MockEngine { request ->
        val (body, status) = answer(request)
        if (body == null) respond("", status) else respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    fun api(): MedAppApi = MedAppApi(medAppHttpClient(engine, "https://medapp.test", retryDelay = { delayMillis(false) { 0L } }))

    /** Заход синхронизации над базой — то же, что делает вход в приложение (PLAN E4), против этого сервера. */
    fun synchronization(database: MedAppDatabase, clock: Clock, schedule: FakeSyncSchedule = FakeSyncSchedule()): Synchronization {
        val api = api()
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        val resolver = PackageSnapshotResolver(vocabulary, database.queueStorage())
        return Synchronization(
            com.kert0n.medapp.queue.QueueWorker(database.queueStorage(), MedAppCourier(MedAppDoor(api), vocabulary, resolver, clock), clock),
            SnapshotApplier(api, database.snapshotStorage(), vocabulary, resolver, clock),
            QueueBacklogRoomStorage(database.syncOperations()),
            schedule,
            clock,
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }
}
