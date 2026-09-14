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

        /**
         * Все обязательства этого пункта. Ответ на пункт снимает их **вместе**: прошлое приводится
         * в порядок раньше записи (F4), поэтому вчерашний пункт успевает стать пропуском и пообещать
         * сказать о себе — а поздний приём по нему разрешён. Снимешь одно напоминание — человек
         * отметит «принял» и получит «вы пропустили».
         */
        fun allOf(intakeId: Uuid): List<NotificationKey> =
            listOf(intake(intakeId, NotificationKind.INTAKE_DUE), intake(intakeId, NotificationKind.INTAKE_MISSED))

        fun expiry(packageId: Uuid, expiresOn: ExpiryDate, kind: NotificationKind): NotificationKey =
            NotificationKey(kind, "$packageId@${expiresOn.lastDay}")

        fun coverage(courseId: Uuid, firstUncoveredAt: Instant, kind: NotificationKind): NotificationKey =
            NotificationKey(kind, "$courseId@$firstUncoveredAt")

        /** Сокращение — событие со своим тождеством (D5): один показ на событие. */
        fun reduction(reductionId: Uuid): NotificationKey = NotificationKey(NotificationKind.COVERAGE_SHORT, reductionId.toString())

        fun digest(date: LocalDate): NotificationKey = NotificationKey(NotificationKind.DAILY_DIGEST, date.toString())
    }
}
