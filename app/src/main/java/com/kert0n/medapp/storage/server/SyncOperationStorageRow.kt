package com.kert0n.medapp.storage.server

import androidx.room.Embedded
import androidx.room.Relation
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.network.value.VocabularyMiss

/**
 * Операция очереди вместе со своими зависимостями.
 *
 * Строка может оказаться нечитаемой целиком, а не только по команде: чужая версия payload после
 * обновления приложения, вид, которого в этой сборке нет, повреждённые параметры подготовленного
 * запроса, предусловие без своей единицы или единица, которой нет в снимке словаря. Поэтому
 * разбор строки — **один** результат: собрать половину операции нельзя, а потерять её молча тем
 * более (PLAN F4).
 */
class SyncOperationStorageRow(
    @Embedded val operation: SyncOperationStorageEntity,
    @Relation(parentColumn = "id", entityColumn = "operation_id")
    val dependencies: List<SyncOperationDependencyStorageEntity> = emptyList()
) {
    fun toDomain(vocabulary: Vocabulary): StoredSyncOperation = try {
        val command = SyncCommandStorageConverter.commandOf(
            kind = operation.kind,
            payload = operation.payload,
            payloadVersion = operation.payloadVersion,
            vocabulary = vocabulary
        ) ?: return unreadable(
            StoredSyncOperation.Reason.Format(
                "команда «${operation.kind}» версии ${operation.payloadVersion} этой сборке неизвестна"
            )
        )
        StoredSyncOperation.Readable(
            SyncOperation(
                id = operation.id,
                command = command,
                sequence = operation.sequence,
                createdAt = operation.createdAt,
                payloadVersion = operation.payloadVersion,
                prepared = operation.prepared?.toDomain(vocabulary),
                groupId = operation.groupId,
                dependsOn = dependencies.mapTo(LinkedHashSet()) { it.dependsOnId },
                status = operation.status,
                attempts = com.kert0n.medapp.domain.value.Attempts(operation.attempts),
                lastError = operation.lastError,
                lastTriedAt = operation.lastTriedAt,
                answer = operation.answerStatus?.let { RawResponse(it, operation.answerBody.orEmpty()) },
                notBefore = operation.notBefore,
                outcomeUnknown = operation.outcomeUnknown,
                refusalReason = operation.refusalReason
            )
        )
    } catch (missed: VocabularyMiss) {
        unreadable(StoredSyncOperation.Reason.VocabularyStale(missed))
    } catch (cause: IllegalArgumentException) {
        // Сюда же приходит SerializationException: повреждённый JSON — её наследник.
        unreadable(StoredSyncOperation.Reason.Format(cause.message ?: "строка очереди не собирается"))
    }

    private fun unreadable(reason: StoredSyncOperation.Reason) =
        StoredSyncOperation.Unreadable(operation.id, reason)
}
