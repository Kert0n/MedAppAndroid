package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Конец эпизода в базе: запись закрывается вместе с удалением плана, брони снимаются со всех
 * источников (PLAN D5, F5). Зовётся внутри транзакции сценария, который лечение закончил: двинул
 * прогресс до конца — подтверждение приёма, изменение лечения, — или отменил его. Что конец
 * наступил и каким исходом, решают [CourseCompletion] и отмена, здесь только запись.
 */
class CourseClosing @Inject constructor(
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val queue: QueueService,
    private val reminders: ReminderWithdrawal
) {

    /**
     * [except] — пачка, чьё снятие брони уже уехало зависимым от расхода: второй раз его не
     * ставят. Кому отвечает пачка, знает она сама, а не ссылка из курса: живая пачка читается
     * той же транзакцией; коробки уже нет — снимать бронь не с чего.
     */
    suspend fun close(course: Course, closing: CourseCompletion.Closing, at: Instant, except: PackageRef? = null) {
        courses.close(closing)
        // Отменённые пункты больше не напоминают о себе: будильники сняты, показанное погашено (PLAN D8).
        for (intake in closing.cancelled) reminders.withdraw(intake.id)
        for (source in course.sources) {
            if (source.pkg == except) continue
            val pkg = packages.find(source.pkg.id) ?: continue
            val released = QueuedCommand(Uuid.random(), PackageSyncCommand.ReleaseClaim(pkg.id))
            queue.change(pkg.medKit, listOf(released), at) { true }
        }
    }
}
