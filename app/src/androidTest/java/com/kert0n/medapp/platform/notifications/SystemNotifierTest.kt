package com.kert0n.medapp.platform.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.PlannedNotification
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.platform.notifications.NotificationChannels.Companion.id
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Пять каналов со своей важностью; уведомление показывается парой `tag = subject`, `id = kind`,
 * и без разрешения показа нет — об этом отвечается, а не молчится (PLAN D8).
 */
@RunWith(AndroidJUnit4::class)
class SystemNotifierTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var database: MedAppDatabase
    private lateinit var notifier: SystemNotifier

    private val expiry = ExpiryDate(LocalDate.of(2027, 3, 31))
    private val planned = PlannedNotification(
        key = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_SOURCE_3D),
        dueAt = Instant.parse("2027-03-28T09:00:00Z"),
        target = NotificationTarget.PackageCard(PACK),
        delivery = NoticeDelivery.SYSTEM
    )

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        database.packageRepository().add(pack(quantity = tablets("20"), expiresOn = expiry))
        notifier = SystemNotifier(context, database.intakeRepository(), database.courseRepository(), database.packageRepository())
        NotificationChannels(context).ensure()
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        database.close()
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Test
    fun everyChannelExistsWithItsImportance() {
        for (channel in NotificationChannel.entries) {
            val system = requireNotNull(manager.getNotificationChannel(channel.id)) { "канала ${channel.id} нет" }
            val expected = when (channel.importance) {
                NotificationChannel.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                NotificationChannel.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                NotificationChannel.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
            }
            assertEquals(channel.name, expected, system.importance)
        }
        // Повторное заведение каналов ничего не ломает.
        NotificationChannels(context).ensure()
        assertEquals(NotificationChannel.entries.size, manager.notificationChannels.count { it.id in NotificationChannel.entries.map { c -> c.id } })
    }

    /** Без разрешения показ невозможен и об этом сказано `false`; с разрешением — показано парой tag/id и гасится ею же. */
    @Test
    fun showingNeedsThePermissionAndUsesTheKeyAsTagAndId() = runTest {
        if (!granted()) {
            assertFalse(notifier.show(planned))
            assertNull(manager.activeNotifications.firstOrNull { it.tag == planned.key.subject })
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }

        assertTrue(notifier.show(planned))

        val shown = requireNotNull(manager.activeNotifications.firstOrNull { it.tag == planned.key.subject }) { "уведомление не показано" }
        assertEquals(NotificationKind.EXPIRY_SOURCE_3D.ordinal, shown.id)
        assertEquals(NotificationChannel.EXPIRY.id, shown.notification.channelId)
        assertTrue(shown.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains("Парацетамол"))

        notifier.dismiss(planned.key)
        assertNull(manager.activeNotifications.firstOrNull { it.tag == planned.key.subject })
    }

    /** Повода больше нет — показывать нечего: коробки нет, и `false` без исключения. */
    @Test
    fun aVanishedSubjectIsNotShown() = runTest {
        val gone = planned.copy(target = NotificationTarget.PackageCard(kotlin.uuid.Uuid.random()))
        assertFalse(notifier.show(gone))
    }
}
