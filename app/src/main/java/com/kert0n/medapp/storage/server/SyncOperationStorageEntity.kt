package com.kert0n.medapp.storage.server

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Строка очереди. Вид команды хранится дискриминатором, её поля — объектом рядом, а версия
 * формата отдельной колонкой: обновление приложения не должно ронять незавершённую очередь
 * (PLAN E2, F4).
 *
 * `sequence` уникален и монотонен — его выдаёт база, и порядок применения задаётся им, а не
 * временем создания. `package_id` называет затронутую пачку и пуст у команд аптечки: порядок по
 * одной упаковке строится запросом, а не доменной функцией. `answer_*` — ответ сервера,
 * записанный до применения: он есть ровно у `ANSWERED`, и закрытие его стирает.
 * `outcome_unknown` — замороженный запрос уходил, и исход неизвестен; сбрасывается вместе с ним.
 */
@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["sequence"], unique = true),
        Index(value = ["package_id", "sequence"]),
        Index("status"),
        Index("group_id")
    ]
)
class SyncOperationStorageEntity(
    @PrimaryKey val id: Uuid,
    val kind: String,
    val payload: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    val sequence: Long,
    val status: SyncOperationStatus,
    val attempts: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "package_id") val packageId: Uuid? = null,
    @ColumnInfo(name = "med_kit_id") val medKitId: Uuid? = null,
    @ColumnInfo(name = "group_id") val groupId: Uuid? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_tried_at") val lastTriedAt: Instant? = null,
    @Embedded(prefix = "prepared_") val prepared: PreparedRequestStorageColumns? = null,
    @ColumnInfo(name = "answer_status") val answerStatus: Int? = null,
    @ColumnInfo(name = "answer_body") val answerBody: String? = null,
    @ColumnInfo(name = "not_before") val notBefore: Instant? = null,
    @ColumnInfo(name = "outcome_unknown", defaultValue = "0") val outcomeUnknown: Boolean = false
)

/**
 * Номер в очереди выдаёт база, поэтому он приходит аргументом: команда его не знает и знать
 * не должна (PLAN E2).
 */
fun SyncOperation.toStorageEntity(
    medKitId: Uuid? = SyncCommandStorageConverter.medKitIdOf(command)
): SyncOperationStorageEntity = SyncOperationStorageEntity(
    id = id,
    kind = SyncCommandStorageConverter.kindOf(command),
    payload = SyncCommandStorageConverter.payloadOf(command),
    payloadVersion = payloadVersion,
    sequence = sequence,
    status = status,
    attempts = attempts,
    createdAt = createdAt,
    packageId = SyncCommandStorageConverter.packageIdOf(command),
    medKitId = medKitId,
    groupId = groupId,
    lastError = lastError,
    lastTriedAt = lastTriedAt,
    prepared = prepared?.toStorageColumns(),
    answerStatus = answer?.status,
    answerBody = answer?.body,
    notBefore = notBefore,
    outcomeUnknown = outcomeUnknown
)
