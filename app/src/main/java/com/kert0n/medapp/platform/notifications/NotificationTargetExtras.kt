package com.kert0n.medapp.platform.notifications

import com.kert0n.medapp.domain.attempt
import android.content.Intent
import com.kert0n.medapp.domain.notification.NotificationTarget
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Цель уведомления в extras намерения — и обратно, в одном месте (PLAN G3, H3). Пишет её
 * [SystemNotifier], читает окно, когда человек нажал карточку: два `when` в разных местах
 * разошлись бы, а здесь новая цель без чтения не компилируется — и наоборот. В extras едут только
 * вид, идентификатор и дата. Действий окно не получает: все они делаются без экрана (C1).
 */
object NotificationTargetExtras {

    fun put(intent: Intent, target: NotificationTarget): Intent {
        for ((key, value) in encode(target)) intent.putExtra(key, value)
        return intent
    }

    /** Цель из намерения; намерение без цели или с незнакомой — `null`: открывается просто приложение. */
    fun read(intent: Intent): NotificationTarget? = decode(extrasOf(intent))

    /**
     * С чем открыто **окно**: цель из шторки — только у свежего запуска и у нового намерения.
     *
     * Восстановленное окно ([windowRestored]) и запуск из недавних (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`)
     * несут исходное намерение системы — с теми же extras, что и в первый раз. Применить его снова
     * значит вернуть человека на карточку, от которой он давно ушёл: после смерти процесса — на уже
     * отвеченный приём (PLAN C1 «Открытие из шторки — один раз»).
     */
    fun launchOpening(intent: Intent, windowRestored: Boolean): NotificationTarget? =
        launchOpening(extrasOf(intent), intent.flags, windowRestored)

    fun launchOpening(extras: Map<String, String>, flags: Int, windowRestored: Boolean): NotificationTarget? {
        if (windowRestored || flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return decode(extras)
    }

    private fun extrasOf(intent: Intent): Map<String, String> =
        listOf(KIND, ID, DATE).mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()

    fun encode(target: NotificationTarget): Map<String, String> = when (target) {
        is NotificationTarget.Intake -> mapOf(KIND to "INTAKE", ID to target.intakeId.toString())
        is NotificationTarget.PackageCard -> mapOf(KIND to "PACKAGE", ID to target.packageId.toString())
        is NotificationTarget.CourseSources -> mapOf(KIND to "COURSE_SOURCES", ID to target.courseId.toString())
        is NotificationTarget.DayPlan -> mapOf(KIND to "DAY_PLAN", DATE to target.date.toString())
        NotificationTarget.SyncStatus -> mapOf(KIND to "SYNC_STATUS")
    }

    fun decode(extras: Map<String, String>): NotificationTarget? {
        val id = extras[ID]?.let { attempt { Uuid.parse(it) }.getOrNull() }
        val date = extras[DATE]?.let { attempt { LocalDate.parse(it) }.getOrNull() }
        return when (extras[KIND]) {
            "INTAKE" -> id?.let { NotificationTarget.Intake(it) }
            "PACKAGE" -> id?.let { NotificationTarget.PackageCard(it) }
            "COURSE_SOURCES" -> id?.let { NotificationTarget.CourseSources(it) }
            "DAY_PLAN" -> date?.let { NotificationTarget.DayPlan(it) }
            "SYNC_STATUS" -> NotificationTarget.SyncStatus
            else -> null
        }
    }

    private const val KIND = "notification_target"
    private const val ID = "notification_target_id"
    private const val DATE = "notification_target_date"
}
