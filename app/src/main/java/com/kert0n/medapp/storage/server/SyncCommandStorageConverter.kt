package com.kert0n.medapp.storage.server

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.unknownRoot
import com.kert0n.medapp.network.value.VocabularyMiss
import com.kert0n.medapp.network.value.formOrMiss
import com.kert0n.medapp.network.value.unitOrMiss
import com.kert0n.medapp.storage.value.storedQuantity
import com.kert0n.medapp.storage.value.toStorageAmount
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Вид команды хранится дискриминатором колонки, а её поля — объектом рядом. Разбор написан
 * руками, потому что доменные величины не носят сериализации: как `Quantity` становится
 * колонкой, знает слой хранения, а не сама величина (PLAN F1, AGENTS).
 *
 * У общего маркера `SyncCommand` исчерпывающего `when` нет — цена деления команд по понятиям
 * (E2). Вместо него набор закрыт круговым тестом по всем двенадцати видам.
 */
object SyncCommandStorageConverter {

    /**
     * Версия формата payload. Незавершённые операции переживают обновление приложения:
     * неизвестную версию работник пропускает и называет, а не роняет процесс (PLAN F4).
     */
    const val PAYLOAD_VERSION = 1

    fun kindOf(command: SyncCommand): String = when (command) {
        is PackageSyncCommand -> when (command) {
            is PackageSyncCommand.Create -> PACKAGE_CREATE
            is PackageSyncCommand.Describe -> PACKAGE_DESCRIBE
            is PackageSyncCommand.CorrectStock -> PACKAGE_CORRECT_STOCK
            is PackageSyncCommand.Move -> PACKAGE_MOVE
            is PackageSyncCommand.Delete -> PACKAGE_DELETE
            is PackageSyncCommand.Withdraw -> PACKAGE_WITHDRAW
            is PackageSyncCommand.Consume -> PACKAGE_CONSUME
            is PackageSyncCommand.SetClaim -> PACKAGE_SET_CLAIM
            is PackageSyncCommand.ReleaseClaim -> PACKAGE_RELEASE_CLAIM
        }
        is MedKitSyncCommand -> when (command) {
            is MedKitSyncCommand.Publish -> MEDKIT_PUBLISH
            is MedKitSyncCommand.Delete -> MEDKIT_DELETE
            is MedKitSyncCommand.Leave -> MEDKIT_LEAVE
        }
        else -> command.unknownRoot()
    }

    /** Какой пачки касается команда; `null` у команд аптечки — порядок по пачке строит запрос. */
    fun packageIdOf(command: SyncCommand): Uuid? = (command as? PackageSyncCommand)?.packageId

    /**
     * Какую полку команда называет **в своём теле**. Это запасной ответ на вопрос «на какой полке
     * команда действует», а не сам этот ответ: полку называет тот, кто команду ставит, — ему это
     * известно, а телу не всегда. У `Move` тело знает только цель, а действует команда на полке,
     * с которой коробку забирают, и её подставляет вызывающий (PLAN E3).
     */
    fun medKitIdOf(command: SyncCommand): Uuid? = when (command) {
        is MedKitSyncCommand -> command.medKitId
        is PackageSyncCommand.Create -> command.medKitId
        is PackageSyncCommand.Move -> command.targetMedKitId
        is PackageSyncCommand.Withdraw -> command.fromMedKitId
        else -> null
    }

    fun payloadOf(command: SyncCommand): String = json.encodeToString(
        JsonObject.serializer(),
        when (command) {
            is PackageSyncCommand -> packagePayload(command)
            is MedKitSyncCommand -> medKitPayload(command)
            else -> command.unknownRoot()
        }
    )

    /**
     * Команда строки очереди. `null` значит ровно одно: этой сборке неизвестен вид команды или
     * версия payload — обычное следствие обновления приложения. Повреждённый payload известного
     * вида — не «команда неизвестна», а ошибка разбора, и она называет себя сама: иначе строка
     * очереди сообщала бы человеку неверную причину (PLAN F4). Единицы и формы payload держит
     * идентификаторами, а объекты им даёт снимок словаря [vocabulary]; промах по нему —
     * [VocabularyMiss], и лечится он чтением словаря, а не решением человека.
     */
    fun commandOf(
        kind: String,
        payload: String,
        payloadVersion: Int,
        vocabulary: Vocabulary
    ): SyncCommand? {
        if (payloadVersion != PAYLOAD_VERSION) return null
        val fields = runCatching { json.parseToJsonElement(payload) as JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("payload команды «$kind» не разбирается")
        return try {
            read(kind, fields, vocabulary)
        } catch (missed: VocabularyMiss) {
            throw missed
        } catch (cause: RuntimeException) {
            throw IllegalArgumentException("поля команды «$kind» не разбираются: ${cause.message}", cause)
        }
    }

    private fun read(kind: String, fields: JsonObject, vocabulary: Vocabulary): SyncCommand? = when (kind) {
        PACKAGE_CREATE -> PackageSyncCommand.Create(
            packageId = fields.uuid("packageId"),
            medKitId = fields.uuid("medKitId"),
            quantity = fields.quantity("quantity", vocabulary),
            facts = fields.facts(vocabulary),
            fromMedKitId = if (fields.containsKey("fromMedKitId")) fields.uuid("fromMedKitId") else null
        )
        PACKAGE_DESCRIBE -> PackageSyncCommand.Describe(
            packageId = fields.uuid("packageId"),
            before = (fields["before"] as JsonObject).facts(vocabulary),
            after = (fields["after"] as JsonObject).facts(vocabulary)
        )
        PACKAGE_CORRECT_STOCK -> PackageSyncCommand.CorrectStock(
            packageId = fields.uuid("packageId"),
            actual = fields.quantity("actual", vocabulary)
        )
        PACKAGE_MOVE -> PackageSyncCommand.Move(
            packageId = fields.uuid("packageId"),
            targetMedKitId = fields.uuid("targetMedKitId")
        )
        PACKAGE_DELETE -> PackageSyncCommand.Delete(packageId = fields.uuid("packageId"))
        PACKAGE_WITHDRAW -> PackageSyncCommand.Withdraw(
            packageId = fields.uuid("packageId"),
            fromMedKitId = fields.uuid("fromMedKitId"),
            carried = fields.quantity("carried", vocabulary)
        )
        PACKAGE_CONSUME -> PackageSyncCommand.Consume(
            packageId = fields.uuid("packageId"),
            amount = Dose(fields.quantity("amount", vocabulary)),
            intakeId = fields.uuid("intakeId"),
            claimAfter = if (fields.containsKey("claimAfter")) fields.quantity("claimAfter", vocabulary) else null
        )
        PACKAGE_SET_CLAIM -> PackageSyncCommand.SetClaim(
            packageId = fields.uuid("packageId"),
            amount = fields.quantity("amount", vocabulary)
        )
        PACKAGE_RELEASE_CLAIM -> PackageSyncCommand.ReleaseClaim(
            packageId = fields.uuid("packageId")
        )
        MEDKIT_PUBLISH -> MedKitSyncCommand.Publish(medKitId = fields.uuid("medKitId"))
        MEDKIT_DELETE -> MedKitSyncCommand.Delete(
            medKitId = fields.uuid("medKitId"),
            transferTo = fields.optionalUuid("transferTo")
        )
        MEDKIT_LEAVE -> MedKitSyncCommand.Leave(medKitId = fields.uuid("medKitId"))
        else -> null
    }

    private fun packagePayload(command: PackageSyncCommand): JsonObject = buildJsonObject {
        put("packageId", JsonPrimitive(command.packageId.toString()))
        when (command) {
            is PackageSyncCommand.Create -> {
                put("medKitId", JsonPrimitive(command.medKitId.toString()))
                command.fromMedKitId?.let { put("fromMedKitId", JsonPrimitive(it.toString())) }
                putQuantity("quantity", command.quantity)
                put("name", JsonPrimitive(command.facts.name))
                putFacts(command.facts)
            }
            is PackageSyncCommand.Describe -> {
                put("before", factsObject(command.before))
                put("after", factsObject(command.after))
            }
            is PackageSyncCommand.CorrectStock -> putQuantity("actual", command.actual)
            is PackageSyncCommand.Move ->
                put("targetMedKitId", JsonPrimitive(command.targetMedKitId.toString()))
            is PackageSyncCommand.Delete -> Unit
            is PackageSyncCommand.Withdraw -> {
                put("fromMedKitId", JsonPrimitive(command.fromMedKitId.toString()))
                putQuantity("carried", command.carried)
            }
            is PackageSyncCommand.Consume -> {
                putQuantity("amount", command.amount.quantity)
                put("intakeId", JsonPrimitive(command.intakeId.toString()))
                command.claimAfter?.let { putQuantity("claimAfter", it) }
            }
            is PackageSyncCommand.SetClaim -> putQuantity("amount", command.amount)
            is PackageSyncCommand.ReleaseClaim -> Unit
        }
    }

    private fun medKitPayload(command: MedKitSyncCommand): JsonObject = buildJsonObject {
        put("medKitId", JsonPrimitive(command.medKitId.toString()))
        if (command is MedKitSyncCommand.Delete) {
            command.transferTo?.let { put("transferTo", JsonPrimitive(it.toString())) }
        }
    }

    private const val PACKAGE_CREATE = "PACKAGE_CREATE"
    private const val PACKAGE_DESCRIBE = "PACKAGE_DESCRIBE"
    private const val PACKAGE_CORRECT_STOCK = "PACKAGE_CORRECT_STOCK"
    private const val PACKAGE_MOVE = "PACKAGE_MOVE"
    private const val PACKAGE_DELETE = "PACKAGE_DELETE"
    private const val PACKAGE_WITHDRAW = "PACKAGE_WITHDRAW"
    private const val PACKAGE_CONSUME = "PACKAGE_CONSUME"
    private const val PACKAGE_SET_CLAIM = "PACKAGE_SET_CLAIM"
    private const val PACKAGE_RELEASE_CLAIM = "PACKAGE_RELEASE_CLAIM"
    private const val MEDKIT_PUBLISH = "MEDKIT_PUBLISH"
    private const val MEDKIT_DELETE = "MEDKIT_DELETE"
    private const val MEDKIT_LEAVE = "MEDKIT_LEAVE"

    private val json = Json

    private fun kotlinx.serialization.json.JsonObjectBuilder.putQuantity(
        name: String,
        quantity: Quantity
    ) {
        put(name, JsonPrimitive(quantity.toStorageAmount()))
        put("${name}UnitId", JsonPrimitive(quantity.unit.id.toString()))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putFacts(facts: PackageSharedFacts) {
        facts.form?.let { put("formId", JsonPrimitive(it.id.toString())) }
        facts.category?.let { put("category", JsonPrimitive(it)) }
        facts.manufacturer?.let { put("manufacturer", JsonPrimitive(it)) }
        facts.country?.let { put("country", JsonPrimitive(it)) }
        facts.description?.let { put("description", JsonPrimitive(it)) }
    }

    private fun factsObject(facts: PackageSharedFacts): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(facts.name))
        putFacts(facts)
    }

    private fun JsonObject.text(name: String): String =
        requireNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.optionalText(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun JsonObject.uuid(name: String): Uuid = Uuid.parse(text(name))

    private fun JsonObject.optionalUuid(name: String): Uuid? = optionalText(name)?.let(Uuid::parse)

    private fun JsonObject.quantity(name: String, vocabulary: Vocabulary): Quantity =
        storedQuantity(text(name), vocabulary.unitOrMiss(uuid("${name}UnitId")))

    private fun JsonObject.facts(vocabulary: Vocabulary): PackageSharedFacts = PackageSharedFacts(
        name = text("name"),
        form = optionalUuid("formId")?.let(vocabulary::formOrMiss),
        category = optionalText("category"),
        manufacturer = optionalText("manufacturer"),
        country = optionalText("country"),
        description = optionalText("description")
    )
}
