package com.kert0n.medapp.storage.medkit

import androidx.room.withTransaction
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.storage.pack.toStorageEntity
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MedKitRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao
) : MedKitStorageRepository {

    /**
     * Полки и их содержимое — одним снимком: список, где свежие полки стоят со старым счётом
     * коробок, в базе никогда не существовал. Содержимое всех полок — один `GROUP BY`, а не
     * запрос на полку (PLAN D2, REQ-055).
     */
    override fun observeAll(today: LocalDate): Flow<List<MedKitProjection>> =
        database.observing(*CONTENTS_TABLES) {
            val contents = medKits.contents(today, medKitId = null).associateBy { it.medKitId }
            medKits.all().map { it.toDomain().projection(contents[it.id]?.toDomain() ?: MedKitContents.EMPTY) }
        }

    override fun observe(id: Uuid, today: LocalDate): Flow<MedKitProjection?> =
        database.observing(*CONTENTS_TABLES) {
            val contents = medKits.contents(today, id).singleOrNull()?.toDomain() ?: MedKitContents.EMPTY
            medKits.find(id)?.toDomain()?.projection(contents)
        }

    override suspend fun find(id: Uuid): MedKit? = medKits.find(id)?.toDomain()

    override fun observeSyncedAt(id: Uuid): Flow<Instant?> = medKits.observe(id).map { it?.syncedAt }

    override suspend fun add(medKit: MedKit) = medKits.upsert(medKit.toStorageEntity(syncedAt = null))

    override suspend fun describe(medKitId: Uuid, name: String, location: String?): Boolean =
        change(medKitId) { it.describe(name, location) }

    override suspend fun mark(medKitId: Uuid, status: MedKitStatus): Boolean = change(medKitId) {
        when (status) {
            MedKitStatus.REMOVING -> it.markRemoving()
            MedKitStatus.PUBLISHING -> it.markPublishing()
            MedKitStatus.ACTIVE -> throw IllegalArgumentException("пометку снимает ответ сервера, а не решение")
        }
    }

    /**
     * Переход применяется к тому, что лежит в базе, и пишется вместе с сохранённой обвязкой: момент
     * сверки принадлежит снимку сервера, а не действию человека (PLAN E4, F5).
     */
    private suspend fun change(medKitId: Uuid, transition: (MedKit) -> MedKit): Boolean =
        database.withTransaction {
            val stored = medKits.find(medKitId) ?: return@withTransaction false
            medKits.upsert(transition(stored.toDomain()).toStorageEntity(stored.syncedAt))
            true
        }

    override suspend fun delete(id: Uuid): Boolean = medKits.delete(id) > 0

    override suspend fun applyServerParticipants(
        id: Uuid,
        participantCount: Long,
        syncedAt: Instant
    ) = medKits.applyServerParticipants(id, participantCount, syncedAt)

    private companion object {
        /** Из чего складывается полка с содержимым: сама полка, живые коробки и их сроки. */
        val CONTENTS_TABLES = arrayOf("med_kits", "packages", "package_details")
    }
}
