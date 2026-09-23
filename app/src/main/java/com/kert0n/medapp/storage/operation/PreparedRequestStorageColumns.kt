package com.kert0n.medapp.storage.operation

import androidx.room.ColumnInfo
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.network.value.unitOrMiss
import com.kert0n.medapp.storage.value.storedQuantity
import com.kert0n.medapp.storage.value.toStorageAmount
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Подготовленный запрос в колонках строки очереди. Отдельной таблицы у него нет: он рождается и
 * умирает вместе со своей операцией и живёт в единственном экземпляре (PLAN E2).
 *
 * Единица предусловий записана рядом с ними: остаток и бронь до запроса измеряются одной
 * единицей, и брать её из пачки позже нельзя — там она уже могла смениться.
 */
class PreparedRequestStorageColumns(
    val method: String,
    val path: String,
    val query: String,
    val body: String? = null,
    @ColumnInfo(name = "drug_version") val drugVersion: Long? = null,
    @ColumnInfo(name = "claims_version") val claimsVersion: Long? = null,
    @ColumnInfo(name = "quantity_before") val quantityBefore: String? = null,
    @ColumnInfo(name = "mine_before") val mineBefore: String? = null,
    @ColumnInfo(name = "unit_id") val unitId: Uuid? = null,
    val at: Instant
) {
    fun toDomain(vocabulary: Vocabulary): PreparedRequest = PreparedRequest(
        method = method,
        path = path,
        query = Json.decodeFromString(queryFormat, query),
        body = body,
        drugVersion = drugVersion?.let(::ResourceVersion),
        claimsVersion = claimsVersion?.let(::ResourceVersion),
        quantityBefore = quantityBefore?.let { storedQuantity(it, requireUnit(vocabulary)) },
        mineBefore = mineBefore?.let { storedQuantity(it, requireUnit(vocabulary)) },
        preparedAt = at
    )

    private fun requireUnit(vocabulary: Vocabulary): QuantityUnit {
        val id = requireNotNull(unitId) { "предусловие по остатку записано вместе со своей единицей" }
        return vocabulary.unitOrMiss(id)
    }
}

fun PreparedRequest.toStorageColumns(): PreparedRequestStorageColumns =
    PreparedRequestStorageColumns(
        method = method,
        path = path,
        query = Json.encodeToString(queryFormat, query),
        body = body,
        drugVersion = drugVersion?.number,
        claimsVersion = claimsVersion?.number,
        quantityBefore = quantityBefore?.toStorageAmount(),
        mineBefore = mineBefore?.toStorageAmount(),
        unitId = unit?.id,
        at = preparedAt
    )

private val queryFormat = MapSerializer(String.serializer(), String.serializer())
