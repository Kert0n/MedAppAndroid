package com.kert0n.medapp.platform.notifications

import android.app.NotificationChannel as SystemChannel
import android.app.NotificationManager
import android.content.Context
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.notification.NotificationChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пять каналов D8, и каждый человек выключает отдельно в системных настройках. Идентификаторы
 * стабильны: канал с новым идентификатором — новый канал, и настройки прежнего пропадут.
 * Заводить каналы повторно безопасно — система хранит выбор человека, а не наши умолчания.
 */
@Singleton
class NotificationChannels @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensure() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ours = NotificationChannel.entries.map { it.id }
        for (channel in NotificationChannel.entries) {
            manager.createNotificationChannel(
                SystemChannel(channel.id, context.getString(channel.title), channel.importance.system)
            )
        }
        // Канал, который приложение перестало объявлять, система держит у себя дальше: у того, кто
        // ставил прежнюю сборку, в настройках остался бы переключатель, не способный ничего
        // показать. Убираем за собой.
        for (stale in manager.notificationChannels.map { it.id } - ours.toSet()) {
            manager.deleteNotificationChannel(stale)
        }
    }

    companion object {
        val NotificationChannel.id: String
            get() = when (this) {
                NotificationChannel.INTAKES -> "intakes"
                NotificationChannel.EXPIRY -> "expiry"
                NotificationChannel.COVERAGE -> "coverage"
                NotificationChannel.DIGEST -> "digest"
                NotificationChannel.SYNC -> "sync"
            }

        private val NotificationChannel.title: Int
            get() = when (this) {
                NotificationChannel.INTAKES -> R.string.channel_intakes
                NotificationChannel.EXPIRY -> R.string.channel_expiry
                NotificationChannel.COVERAGE -> R.string.channel_coverage
                NotificationChannel.DIGEST -> R.string.channel_digest
                NotificationChannel.SYNC -> R.string.channel_sync
            }

        private val NotificationChannel.Importance.system: Int
            get() = when (this) {
                NotificationChannel.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                NotificationChannel.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                NotificationChannel.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
            }
    }
}
