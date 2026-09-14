package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.domain.pack.ExpiryDate
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Тождество уведомления: вид и предмет (PLAN D8). Для системы это стабильная пара
 * `tag = subject`, `id = kind` — а не один 32-битный хеш, у которого два разных уведомления
 * могли бы затереть друг друга. Дата события входит в предмет: исправленный срок годности или
 * сдвинувшаяся нехватка — новое уведомление, а прежнее отменяется.
 */
data class NotificationKey(val kind: NotificationKind, val subject: String) {

    init {
        require(subject.isNotBlank()) { "у уведомления есть предмет" }
    }

    companion object {
        fun intake(intakeId: Uuid, kind: NotificationKind): NotificationKey = NotificationKey(kind, intakeId.toString())

        fun expiry(packageId: Uuid, expiresOn: ExpiryDate, kind: NotificationKind): NotificationKey =
            NotificationKey(kind, "$packageId@${expiresOn.lastDay}")

        fun coverage(courseId: Uuid, firstUncoveredAt: Instant, kind: NotificationKind): NotificationKey =
            NotificationKey(kind, "$courseId@$firstUncoveredAt")

        /** Сокращение — событие со своим тождеством (D5): один показ на событие. */
        fun reduction(reductionId: Uuid): NotificationKey = NotificationKey(NotificationKind.COVERAGE_SHORT, reductionId.toString())

        fun digest(date: LocalDate): NotificationKey = NotificationKey(NotificationKind.DAILY_DIGEST, date.toString())
    }
}
