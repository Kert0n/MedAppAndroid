package com.kert0n.medapp.storage.server

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import java.time.Instant
import javax.inject.Inject

class NotificationLogRoomRepository @Inject constructor(
    private val log: NotificationLogDao
) : NotificationLogStorageRepository {

    override suspend fun wasShown(key: NotificationKey, delivery: NoticeDelivery): Boolean =
        log.wasShown(key.stored, delivery.name)

    override suspend fun remember(key: NotificationKey, delivery: NoticeDelivery, shownAt: Instant) {
        log.remember(NotificationLogStorageEntity(key.stored, delivery.name, key.kind.name, shownAt))
    }

    override suspend fun forget(key: NotificationKey) = log.forget(key.stored)

    /** Ключ строкой: вид и предмет вместе, чтобы разные этапы одного события не склеивались. */
    private val NotificationKey.stored: String get() = "${kind.name}:$subject"
}
