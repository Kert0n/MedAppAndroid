package com.kert0n.medapp.storage.server

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import java.time.Instant

/**
 * Журнал показов (PLAN D8): что человек уже видел — каким способом и когда. Без него ежедневный
 * проход сообщал бы об одной просрочке каждый день, а после простоя выдал бы залп старых
 * напоминаний. Пишется **после** показа (C1): падение между ними даёт повтор той же парой
 * `tag/id`, а не потерю.
 */
interface NotificationLogStorageRepository {

    suspend fun wasShown(key: NotificationKey, delivery: NoticeDelivery): Boolean

    /** Первый показ побеждает: повтор той же пары момента не двигает. */
    suspend fun remember(key: NotificationKey, delivery: NoticeDelivery, shownAt: Instant)

    /** Показанное снимается вместе со своим поводом: курс отменён, приём отвечен. */
    suspend fun forget(key: NotificationKey)
}
