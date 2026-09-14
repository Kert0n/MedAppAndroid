package com.kert0n.medapp.platform.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.notification.Delivery
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.platform.notifications.NotificationChannels.Companion.id
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first

/**
 * Системное уведомление из [Reminder]. Чем кончился показ, отвечается значением [Delivery]: «нет
 * разрешения» и «повода больше нет» — разные случаи, и владелец доставки поступает с ними
 * по-разному (PLAN D8, C1).
 * текст — из строк, данные — из чтений хранения,
 * пара `tag = subject`, `id = kind` — из ключа (PLAN D8). В `PendingIntent` едут только
 * идентификаторы: что открыть по ним, решает приложение (G3, H3). Без разрешения на уведомления
 * показа нет — и об этом отвечается `false`, а не молчанием, чтобы журнал не записал непоказанное.
 */
@Singleton
class SystemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intakes: IntakeStorageRepository,
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository
) : Notifier {

    override suspend fun show(notification: Reminder): Delivery {
        // Проверка стоит здесь, а не в отдельном методе: lint видит её только рядом с `notify`.
        // `POST_NOTIFICATIONS` — разрешение только с Android 13; ниже его нет, и спрашивать надо систему.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Delivery.NOT_ALLOWED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return Delivery.NOT_ALLOWED
        // Разрешение приложению — ещё не разрешение этому разговору: каналов четыре, и человек
        // выключает их по отдельности (PLAN D8). Выключенный канал молчит, а `notify` об этом не
        // скажет — спрашиваем канал **этого вида**.
        val channel = context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(notification.channel.id)
        if (channel == null || channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
            return Delivery.NOT_ALLOWED
        }
        // Текст собирается из чтений по идентификаторам цели: не нашлось — повода больше нет.
        val text = textOf(notification) ?: return Delivery.SUBJECT_GONE
        val builder = NotificationCompat.Builder(context, notification.channel.id)
            .setSmallIcon(R.drawable.ic_notification_medication)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setAutoCancel(true)
            .setContentIntent(openIntent(notification))
        for (action in notification.actions) {
            val intake = (notification.target as? NotificationTarget.Intake)?.intakeId ?: continue
            actionIntent(intake, action, notification.key)?.let { builder.addAction(0, context.getString(action.label), it) }
        }
        NotificationManagerCompat.from(context).notify(notification.key.subject, notification.kind.ordinal, builder.build())
        return Delivery.SHOWN
    }

    override suspend fun dismiss(key: NotificationKey) {
        NotificationManagerCompat.from(context).cancel(key.subject, key.kind.ordinal)
    }

    private class Text(val title: String, val body: String)

    /** Данные для текста — чтением по идентификаторам из цели; повода больше нет — показывать нечего. */
    private suspend fun textOf(notification: Reminder): Text? = when (val target = notification.target) {
        is NotificationTarget.Intake -> {
            val intake = intakes.find(target.intakeId) as? com.kert0n.medapp.domain.intake.CourseIntake ?: return null
            val title = courses.findRecord(intake.courseId)?.title ?: return null
            val dose = "${intake.plannedAmount.quantity.amount.stripTrailingZeros().toPlainString()} ${intake.plannedAmount.unit.name}"
            val pkg = intake.plannedPackage?.name
            when (notification.kind) {
                NotificationKind.INTAKE_DUE -> Text(
                    context.getString(R.string.notice_intake_due_title, title),
                    if (pkg != null) context.getString(R.string.notice_intake_due_body, dose, pkg) else context.getString(R.string.notice_intake_due_unsupplied, dose)
                )
                else -> Text(context.getString(R.string.notice_intake_missed_title, title), context.getString(R.string.notice_intake_missed_body, dose))
            }
        }
        is NotificationTarget.PackageCard -> {
            val pkg = packages.observe(target.packageId).first() ?: return null
            val until = pkg.facts.expiresOn?.lastDay?.format(DATE) ?: return null
            Text(
                context.getString(
                    when (notification.kind) {
                        NotificationKind.EXPIRY_SOURCE_3D -> R.string.notice_expiry_3d_title
                        NotificationKind.EXPIRY_SOURCE_1D -> R.string.notice_expiry_1d_title
                        else -> R.string.notice_expiry_today_title
                    },
                    pkg.name
                ),
                context.getString(R.string.notice_expiry_body, until)
            )
        }
        is NotificationTarget.CourseSources -> {
            val record = courses.findRecord(target.courseId) ?: return null
            val coverage = courses.observeCoverage(target.courseId).first()
            val zone = record.prescription.schedule.zone
            val until = coverage?.coveredUntil?.atZone(zone)?.toLocalDate()?.format(DATE)
            val body = if (until != null) context.getString(R.string.notice_coverage_body_until, until) else context.getString(R.string.notice_coverage_body_none)
            Text(
                context.getString(
                    when (notification.kind) {
                        NotificationKind.COVERAGE_SHORT -> R.string.notice_coverage_short_title
                        NotificationKind.COVERAGE_3D -> R.string.notice_coverage_3d_title
                        else -> R.string.notice_coverage_end_title
                    },
                    record.title
                ),
                body
            )
        }
        is NotificationTarget.DayPlan -> Text(context.getString(R.string.notice_digest_title), context.getString(R.string.notice_digest_body, target.date.format(DATE)))
        NotificationTarget.SyncStatus -> Text(context.getString(R.string.notice_sync_attention_title), context.getString(R.string.notice_sync_attention_body))
    }

    /**
     * Открыть приложение по цели: только идентификаторы и дата в extras (G3), и кладёт их тот же
     * [NotificationTargetExtras], который их потом читает. Платформа не знает экранов и их
     * Activity (H1) — открывается то, что пакет объявил точкой входа.
     */
    private fun openIntent(notification: Reminder): PendingIntent {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) { "у приложения есть точка входа" }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        NotificationTargetExtras.put(intent, notification.target)
        return PendingIntent.getActivity(context, notification.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Действие едет своему приёмнику; в extras — только идентификатор пункта (G3). */
    private fun actionIntent(intakeId: Uuid, action: NotificationAction, key: NotificationKey): PendingIntent? {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action.name)
            .putExtra(NotificationActionReceiver.EXTRA_INTAKE_ID, intakeId.toString())
        return PendingIntent.getBroadcast(context, key.hashCode() * 31 + action.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private val NotificationAction.label: Int
        get() = when (this) {
            NotificationAction.SKIP -> R.string.action_skip
            NotificationAction.SNOOZE -> R.string.action_snooze
        }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}
