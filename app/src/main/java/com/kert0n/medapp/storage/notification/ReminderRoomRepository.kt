package com.kert0n.medapp.storage.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.PendingNotice
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.observing
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReminderRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val reminders: ReminderDao
) : ReminderStorageRepository {

    override fun changes(): Flow<Unit> =
        database.invalidationTracker.createFlow("reminders", emitInitialState = false).map { }

    override fun groundsChanged(): Flow<Unit> =
        database.invalidationTracker.createFlow(*GROUNDS, emitInitialState = false).map { }

    override suspend fun find(key: NotificationKey): Reminder? = reminders.find(key.stored)?.toDomain()

    override suspend fun findAll(keys: Collection<NotificationKey>): List<Reminder> =
        if (keys.isEmpty()) emptyList() else reminders.findAll(keys.map { it.stored }).map { it.toDomain() }

    override suspend fun awaiting(delivery: NoticeDelivery): List<Reminder> =
        reminders.awaiting(delivery.name).map { it.toDomain() }

    override fun observeAwaiting(delivery: NoticeDelivery): Flow<List<PendingNotice>> =
        database.observing("reminders") { awaiting(delivery).map { it.projection() } }

    override suspend fun withdrawn(): List<Reminder> = reminders.withdrawn().map { it.toDomain() }

    override suspend fun stale(before: Instant): List<Reminder> = reminders.olderThan(before).map { it.toDomain() }

    override suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder> =
        reminders.ofKinds(kinds.map { it.name }).map { it.toDomain() }

    override suspend fun saveAll(reminders: Collection<Reminder>) {
        if (reminders.isNotEmpty()) this.reminders.saveAll(reminders.map { it.toStorageEntity() })
    }

    override suspend fun deleteAll(keys: Collection<NotificationKey>) {
        if (keys.isNotEmpty()) reminders.deleteAll(keys.map { it.stored })
    }
}

/**
 * Таблицы, из которых сверка выводит обещанное: годность и назначение — коробки, обеспечение —
 * лечение с пунктами, бронями и очередью, сокращения, внимание к очереди; словарь — потому что по
 * нему собираются проекции. `reminders` здесь нет намеренно.
 */
private val GROUNDS = arrayOf(
    "packages", "package_details", "claims", "med_kits",
    "courses", "course_sources", "course_times", "active_package_assignments", "coverage_reductions",
    "intakes", "sync_operations", "quantity_units", "form_types"
)

/** Ключ строкой: вид и предмет вместе, чтобы разные этапы одного события не склеивались. */
private val NotificationKey.stored: String get() = "${kind.name}:$subject"

private fun Reminder.toStorageEntity() = ReminderStorageEntity(
    key = key.stored,
    kind = key.kind.name,
    delivery = delivery.name,
    subject = key.subject,
    targetKind = target.stored,
    targetId = target.id,
    targetDate = (target as? NotificationTarget.DayPlan)?.date,
    dueAt = dueAt,
    state = state.name,
    shownAt = shownAt,
    notBefore = notBefore,
    attempts = attempts.count
)

private fun ReminderStorageEntity.toDomain() = Reminder(
    key = NotificationKey(NotificationKind.valueOf(kind), subject),
    target = targetOf(targetKind, targetId, targetDate),
    dueAt = dueAt,
    state = Reminder.State.valueOf(state),
    shownAt = shownAt,
    notBefore = notBefore,
    attempts = Attempts(attempts)
)

private val NotificationTarget.stored: String
    get() = when (this) {
        is NotificationTarget.Intake -> "INTAKE"
        is NotificationTarget.PackageCard -> "PACKAGE"
        is NotificationTarget.CourseSources -> "COURSE_SOURCES"
        is NotificationTarget.DayPlan -> "DAY_PLAN"
        NotificationTarget.SyncStatus -> "SYNC_STATUS"
    }

private val NotificationTarget.id: Uuid?
    get() = when (this) {
        is NotificationTarget.Intake -> intakeId
        is NotificationTarget.PackageCard -> packageId
        is NotificationTarget.CourseSources -> courseId
        is NotificationTarget.DayPlan -> null
        NotificationTarget.SyncStatus -> null
    }

private fun targetOf(kind: String, id: Uuid?, date: LocalDate?): NotificationTarget = when (kind) {
    "INTAKE" -> NotificationTarget.Intake(requireNotNull(id) { "у цели-пункта есть идентификатор" })
    "PACKAGE" -> NotificationTarget.PackageCard(requireNotNull(id) { "у цели-коробки есть идентификатор" })
    "COURSE_SOURCES" -> NotificationTarget.CourseSources(requireNotNull(id) { "у цели-лечения есть идентификатор" })
    "DAY_PLAN" -> NotificationTarget.DayPlan(requireNotNull(date) { "у цели-дня есть дата" })
    "SYNC_STATUS" -> NotificationTarget.SyncStatus
    else -> error("незнакомая цель уведомления: $kind")
}
