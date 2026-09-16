package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.PendingNotice
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.RecordedIntake
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Порты, до которых проверке дела нет, — и которые она **обязана** не задеть.
 *
 * Собрать сценарий можно только целиком: у начала лечения в соседях календарь пунктов и
 * обязательства уведомлений. Редактор черновика их не трогает, и подделка это утверждает: позвали
 * — значит, путь разъехался с моделью, и проверка падает с именем метода, а не тихо зеленеет на
 * выдуманном ответе.
 */
object UnaskedIntakes : IntakeStorageRepository {

    override fun observeOfCourse(courseId: Uuid): Flow<List<IntakeProjection>> = emptyFlow()

    override fun observeOfPackage(packageId: Uuid): Flow<List<IntakeProjection>> = emptyFlow()

    override fun observeOfIds(ids: Set<Uuid>): Flow<List<IntakeProjection>> = emptyFlow()

    override suspend fun ofCourse(courseId: Uuid): List<Intake> = unasked("ofCourse")

    override suspend fun find(id: Uuid): Intake? = unasked("find")

    override suspend fun syncStateOf(id: Uuid): IntakeSyncState? = unasked("syncStateOf")

    override suspend fun save(recorded: RecordedIntake) = unasked("save")

    override suspend fun materialise(planned: List<CourseIntake>): List<Uuid> = unasked("materialise")

    override suspend fun plannedBefore(until: Instant): List<CourseIntake> = unasked("plannedBefore")

    override suspend fun prunePlanned(courseId: Uuid, keep: Set<ScheduledOccurrence>): List<Uuid> =
        unasked("prunePlanned")

    override suspend fun record(outcome: IntakeOutcome): Boolean = unasked("record")
}

/** Обязательства уведомлений: те же правила, что у [UnaskedIntakes]. */
object UnaskedReminders : ReminderStorageRepository {

    override fun changes(): Flow<Unit> = emptyFlow()

    override fun groundsChanged(): Flow<Unit> = emptyFlow()

    override suspend fun find(key: NotificationKey): Reminder? = unasked("find")

    override suspend fun findAll(keys: Collection<NotificationKey>): List<Reminder> = unasked("findAll")

    override suspend fun awaiting(delivery: NoticeDelivery): List<Reminder> = unasked("awaiting")

    override fun observeAwaiting(delivery: NoticeDelivery): Flow<List<PendingNotice>> = emptyFlow()

    override suspend fun groundless(): List<Reminder> = unasked("groundless")

    override suspend fun stale(before: Instant): List<Reminder> = unasked("stale")

    override suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder> = unasked("ofKinds")

    override suspend fun saveAll(reminders: Collection<Reminder>) = unasked("saveAll")

    override suspend fun deleteAll(keys: Collection<NotificationKey>) = unasked("deleteAll")
}

/** Настройки уведомлений по умолчанию: спрашивать их редактору черновика тоже незачем. */
object QuietNotificationSettings : NotificationSettingsSource {
    override suspend fun current(): NotificationSettings = NotificationSettings.DEFAULT
}

private fun unasked(method: String): Nothing =
    error("этот путь не трогает порт, а спросил «$method» — модель разъехалась с проверкой")
