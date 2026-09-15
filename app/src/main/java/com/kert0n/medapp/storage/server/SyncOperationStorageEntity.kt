package com.kert0n.medapp.storage.server

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationState
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
 * `refusal_reason` — причина отказа перечислением, как и `status`; есть ровно у `REFUSED`, а
 * `last_error` остаётся журналу (PLAN E2).
 */
@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["sequence"], unique = true),
        Index(value = ["package_id", "sequence"]),
        Index("status"),
        Index("group_id"),
        // Свои команды полка считает по ключу, а не перебором всей истории очереди.
        Index("med_kit_id")
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
    @ColumnInfo(name = "outcome_unknown", defaultValue = "0") val outcomeUnknown: Boolean = false,
    @ColumnInfo(name = "refusal_reason") val refusalReason: RefusalReason? = null,
    /** Отказ разобран человеком в этот момент: строка остаётся, экрану и вниманию к очереди она больше не нужна (C1). */
    @ColumnInfo(name = "dismissed_at") val dismissedAt: Instant? = null
)

/**
 * Состояние отправки — из колонок, без словаря: его переходы применимы и к строке, которую нечем
 * прочитать (PLAN C1 «Переходы операции — у типа»).
 *
 * Колонки могут разойтись между собой — и лишним, и недостающим: записанный ответ у ждущей,
 * причина отказа у применённой, `ANSWERED` без ответа, `REFUSED` без причины. Такого состояния не
 * бывает, и строгий тип его не выражает. Строка от этого не перестаёт существовать: её надо
 * показать человеку и дать закрыть, а одна порченая строка не должна останавливать чтение очереди
 * (PLAN F4). Поэтому здесь читается то, что в строке **бесспорно**: статус, подтверждённый своей
 * колонкой, попытки, сроки, есть ли запрос; статус, которому его колонка противоречит, бесспорным
 * не считается — не отвеченная на деле строка остаётся ждущей, а закрытая без вида отказа остаётся
 * закрытой утратой доступа, — а лишнее отбрасывается. Собранная операция строит своё состояние
 * сама и строго: противоречие делает её нечитаемой, а не чинит её.
 */
fun SyncOperationStorageEntity.toState(): SyncOperationState {
    val answer = answerStatus?.let { RawResponse(it, answerBody.orEmpty()) }
    val undisputed = when {
        // Ответа нет — значит, его и не получали: операция ждёт.
        status == SyncOperationStatus.ANSWERED && answer == null -> SyncOperationStatus.PENDING
        // Закрытость бесспорна, вид закрытия — нет: отказ без причины не выражается.
        status == SyncOperationStatus.REFUSED && refusalReason == null -> SyncOperationStatus.ACCESS_LOST
        else -> status
    }
    return SyncOperationState(
        status = undisputed,
        attempts = Attempts(attempts),
        lastError = lastError,
        lastTriedAt = lastTriedAt,
        answer = answer.takeIf { undisputed == SyncOperationStatus.ANSWERED },
        notBefore = notBefore,
        outcomeUnknown = outcomeUnknown && prepared != null,
        refusalReason = refusalReason.takeIf { undisputed == SyncOperationStatus.REFUSED },
        hasRequest = prepared != null
    )
}

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
    attempts = attempts.count,
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
    outcomeUnknown = outcomeUnknown,
    refusalReason = refusalReason
)
