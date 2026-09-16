package com.kert0n.medapp.platform.notifications

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.platform.notifications.NotificationChannels.Companion.id
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Можно ли показать в канале (PLAN D8): выключенный человеком канал молчит, а `notify()` об этом
 * не скажет.
 *
 * Спрашивается на **своём** канале, а не на одном из пяти наших: выключенный канал не вернуть —
 * система помнит его настройки и после удаления, а поднять важность приложение не вправе. Проверка,
 * заглушившая настоящий канал, портила бы устройство до переустановки приложения (найдено
 * прогонами 2026-09-16).
 */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channels = NotificationChannels(context)

    /** Канал только этой проверки: с нашими он не пересекается ни именем, ни судьбой. */
    private val mine = "test-muted-channel"

    @After
    fun tearDown() {
        manager.deleteNotificationChannel(mine)
    }

    /** Выключенный канал — «показать нельзя»: человек мог оставить сводку и запретить приёмы. */
    @Test
    fun aMutedChannelCannotShow() {
        manager.createNotificationChannel(
            android.app.NotificationChannel(mine, "Проверка", NotificationManager.IMPORTANCE_NONE)
        )

        assertTrue(channels.mutedOrMissing(mine))
    }

    /** Канала нет вовсе — показывать некуда, и это тот же ответ. */
    @Test
    fun aMissingChannelCannotShow() {
        manager.deleteNotificationChannel(mine)

        assertTrue(channels.mutedOrMissing(mine))
    }

    /** Живой канал показывать не мешает: иначе приложение молчало бы при всех разрешениях. */
    @Test
    fun anOrdinaryChannelCanShow() {
        channels.ensure()

        assertFalse(channels.mutedOrMissing(NotificationChannel.INTAKES.id))
    }
}
