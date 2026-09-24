package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.pack.PackageRef
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
    private val courses: CourseRecords,
    private val following: CourseFollowing,
    private val reminders: com.kert0n.medapp.feature.notification.ReminderWithdrawal
) {

    /**
     * [except] — пачка, чьё снятие брони уже уехало зависимым от расхода: второй раз его не
     * ставят. Брони конца — разница к «ничего не выделено», и ставит их единственный владелец.
     */
    suspend fun close(course: Course, closing: CourseCompletion.Closing, at: Instant, except: PackageRef? = null): List<Uuid> {
        courses.close(closing)
        following.announceClaims(course, course.unallocated(), at, except)
        // Отменённые пункты больше не напоминают о себе: обязательство отзывается **этой же**
        // транзакцией, а гасит карточки и переставляет будильник владелец доставки — после
        // фиксации, по сигналу изменившейся таблицы (PLAN D8, F5).
        val cancelled = closing.cancelled.map { it.id }
        reminders.withdrawAll(cancelled)
        return cancelled
    }
}
