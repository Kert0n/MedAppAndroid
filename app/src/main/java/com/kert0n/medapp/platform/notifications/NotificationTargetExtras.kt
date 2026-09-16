package com.kert0n.medapp.platform.notifications

import com.kert0n.medapp.domain.attempt
import android.content.Intent
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationOpening
import com.kert0n.medapp.domain.notification.NotificationTarget
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Цель уведомления и действие человека в extras намерения — и обратно, в одном месте (PLAN G3,
 * H3). Пишет их [SystemNotifier], читает оболочка, когда человек нажал карточку или «Принял»
 * (U5): два `when` в разных местах разошлись бы, а здесь новая цель без чтения не компилируется —
 * и наоборот. В extras едут только вид, идентификатор, дата и имя действия.
 */
object NotificationTargetExtras {

    fun put(intent: Intent, target: NotificationTarget, action: NotificationAction? = null): Intent {
        for ((key, value) in encode(target, action)) intent.putExtra(key, value)
        return intent
    }

    /** Цель и действие из намерения; намерение без цели или с незнакомой — `null`: открывается просто приложение. */
    fun read(intent: Intent): NotificationOpening? = decode(
        listOf(KIND, ID, DATE, ACTION).mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
    )

    fun encode(target: NotificationTarget, action: NotificationAction? = null): Map<String, String> {
        val fields = when (target) {
            is NotificationTarget.Intake -> mapOf(KIND to "INTAKE", ID to target.intakeId.toString())
            is NotificationTarget.PackageCard -> mapOf(KIND to "PACKAGE", ID to target.packageId.toString())
            is NotificationTarget.CourseSources -> mapOf(KIND to "COURSE_SOURCES", ID to target.courseId.toString())
            is NotificationTarget.DayPlan -> mapOf(KIND to "DAY_PLAN", DATE to target.date.toString())
            NotificationTarget.SyncStatus -> mapOf(KIND to "SYNC_STATUS")
        }
        return if (action == null) fields else fields + (ACTION to action.name)
    }

    fun decode(extras: Map<String, String>): NotificationOpening? {
        val id = extras[ID]?.let { attempt { Uuid.parse(it) }.getOrNull() }
        val date = extras[DATE]?.let { attempt { LocalDate.parse(it) }.getOrNull() }
        val target = when (extras[KIND]) {
            "INTAKE" -> id?.let { NotificationTarget.Intake(it) }
            "PACKAGE" -> id?.let { NotificationTarget.PackageCard(it) }
            "COURSE_SOURCES" -> id?.let { NotificationTarget.CourseSources(it) }
            "DAY_PLAN" -> date?.let { NotificationTarget.DayPlan(it) }
            "SYNC_STATUS" -> NotificationTarget.SyncStatus
            else -> null
        } ?: return null
        // Незнакомое действие — не повод терять цель: открывается карточка, как по нажатию на неё.
        val action = extras[ACTION]?.let { name -> NotificationAction.entries.firstOrNull { it.name == name } }
        return NotificationOpening(target, action)
    }

    private const val KIND = "notification_target"
    private const val ID = "notification_target_id"
    private const val DATE = "notification_target_date"
    private const val ACTION = "notification_action"
}
