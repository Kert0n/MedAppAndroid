package com.kert0n.medapp.storage.medkit

import androidx.room.withTransaction
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.pack.toStorageEntity
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MedKitRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao
) : MedKitStorageRepository {

    override fun observeAll(): Flow<List<MedKitProjection>> =
        medKits.observeAll().map { rows -> rows.map { it.toDomain().projection() } }

    override fun observe(id: Uuid): Flow<MedKitProjection?> =
        medKits.observe(id).map { it?.toDomain()?.projection() }

    override suspend fun find(id: Uuid): MedKit? = medKits.find(id)?.toDomain()

    override fun observeSyncedAt(id: Uuid): Flow<Instant?> = medKits.observe(id).map { it?.syncedAt }

    override suspend fun save(medKit: MedKit, syncedAt: Instant?) =
        medKits.upsert(medKit.toStorageEntity(syncedAt))

    override suspend fun mark(medKitId: Uuid, status: MedKitStatus): Boolean = database.withTransaction {
        val stored = medKits.find(medKitId) ?: return@withTransaction false
        val marked = when (status) {
            MedKitStatus.REMOVING -> stored.toDomain().markRemoving()
            MedKitStatus.PUBLISHING -> stored.toDomain().markPublishing()
            MedKitStatus.ACTIVE -> throw IllegalArgumentException("пометку снимает ответ сервера, а не решение")
        }
        medKits.upsert(marked.toStorageEntity(stored.syncedAt))
        true
    }

    override suspend fun delete(id: Uuid): Boolean = medKits.delete(id) > 0

    override suspend fun applyServerParticipants(
        id: Uuid,
        participantCount: Long,
        syncedAt: Instant
    ) = medKits.applyServerParticipants(id, participantCount, syncedAt)
}
