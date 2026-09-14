package com.kert0n.medapp.storage.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.storage.database.MedAppDatabase
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

    override suspend fun raiseAll(reminders: Collection<Reminder>) {
        for (reminder in reminders) this.reminders.raise(reminder.toStorageEntity())
    }

    override suspend fun find(key: NotificationKey): Reminder? = reminders.find(key.stored)?.toDomain()

    override suspend fun due(now: Instant): List<Reminder> = reminders.due(now).map { it.toDomain() }

    override suspend fun nextDueAt(): Instant? = reminders.nextDueAt()

    override suspend fun withdrawn(): List<Reminder> = reminders.withdrawn().map { it.toDomain() }

    override suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder> =
        reminders.ofKinds(kinds.map { it.name }).map { it.toDomain() }

    override suspend fun defer(key: NotificationKey, until: Instant) {
        reminders.defer(key.stored, until)
    }

    override suspend fun markShown(key: NotificationKey, at: Instant) {
        reminders.markShown(key.stored, at)
    }

    override suspend fun withdraw(keys: Collection<NotificationKey>) {
        if (keys.isNotEmpty()) reminders.withdraw(keys.map { it.stored })
    }

    override suspend fun forget(keys: Collection<NotificationKey>) {
        if (keys.isNotEmpty()) reminders.forget(keys.map { it.stored })
    }

    override suspend fun forgetShownBefore(before: Instant) {
        reminders.forgetShownBefore(before)
    }
}

/** Ключ строкой: вид и предмет вместе, чтобы разные этапы одного события не склеивались. */
private val NotificationKey.stored: String get() = "${kind.name}:$subject"

private fun Reminder.toStorageEntity() = ReminderStorageEntity(
    key = key.stored,
    kind = key.kind.name,
    subject = key.subject,
    targetKind = target.stored,
    targetId = target.id,
    targetDate = (target as? NotificationTarget.DayPlan)?.date,
    dueAt = dueAt,
    state = state.name,
    shownAt = shownAt
)

private fun ReminderStorageEntity.toDomain() = Reminder(
    key = NotificationKey(NotificationKind.valueOf(kind), subject),
    target = targetOf(targetKind, targetId, targetDate),
    dueAt = dueAt,
    state = Reminder.State.valueOf(state),
    shownAt = shownAt
)

private val NotificationTarget.stored: String
    get() = when (this) {
        is NotificationTarget.Intake -> "INTAKE"
        is NotificationTarget.PackageCard -> "PACKAGE"
        is NotificationTarget.CourseSources -> "COURSE_SOURCES"
        is NotificationTarget.DayPlan -> "DAY_PLAN"
        NotificationTarget.SyncStatus -> "SYNC"
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
    "SYNC" -> NotificationTarget.SyncStatus
    else -> NotificationTarget.DayPlan(requireNotNull(date) { "у цели-дня есть дата" })
}
