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
import com.kert0n.medapp.domain.notification.Reminder
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
    private val planned = Reminder(
        key = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_SOURCE_3D),
        target = NotificationTarget.PackageCard(PACK),
        dueAt = Instant.parse("2027-03-28T09:00:00Z")
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

    /** До Android 13 разрешения нет — считается выданным; выше — как ответит система. */
    private fun granted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
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
            // Без разрешения показ не проходит; что висит в шторке с прежней установки, показ не трогает.
            val before = manager.activeNotifications.count { it.tag == planned.key.subject }
            assertEquals(com.kert0n.medapp.domain.notification.Delivery.NOT_ALLOWED, notifier.show(planned))
            assertEquals(before, manager.activeNotifications.count { it.tag == planned.key.subject })
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            manager.cancelAll()
        }

        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SHOWN, notifier.show(planned))

        val shown = requireNotNull(awaitShown(planned.key.subject)) { "уведомление не показано" }
        assertEquals(NotificationKind.EXPIRY_SOURCE_3D.ordinal, shown.id)
        assertEquals(NotificationChannel.EXPIRY.id, shown.notification.channelId)
        assertTrue(shown.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains("Парацетамол"))

        notifier.dismiss(planned.key)
        assertNull(awaitShown(planned.key.subject, expected = false))
    }

    /** Внимание к очереди — карточка на канале `sync`, без данных в цели: очередь одна (H3 №28). */
    @Test
    fun syncAttentionIsShownOnItsOwnChannel() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val attention = Reminder(NotificationKey.sync(kotlin.uuid.Uuid.random()), NotificationTarget.SyncStatus, planned.dueAt)

        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SHOWN, notifier.show(attention))

        val shown = requireNotNull(awaitShown(attention.key.subject)) { "уведомление не показано" }
        assertEquals(NotificationChannel.SYNC.id, shown.notification.channelId)
        assertEquals(NotificationKind.SYNC_ATTENTION.ordinal, shown.id)
        notifier.dismiss(attention.key)
    }

    /** Система показывает и гасит асинхронно: ждём, но недолго. */
    private fun awaitShown(tag: String, expected: Boolean = true): android.service.notification.StatusBarNotification? {
        repeat(40) {
            val found = manager.activeNotifications.firstOrNull { it.tag == tag }
            if ((found != null) == expected) return found
            Thread.sleep(50)
        }
        return manager.activeNotifications.firstOrNull { it.tag == tag }
    }

    /**
     * Повода больше нет — показывать нечего, и это **не** отказ в разрешении: владелец доставки
     * такое обязательство снимает, а не ждёт с ним человека (PLAN D8, C1).
     */
    @Test
    fun aVanishedSubjectIsNotShown() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val gone = Reminder(planned.key, NotificationTarget.PackageCard(kotlin.uuid.Uuid.random()), planned.dueAt)

        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SUBJECT_GONE, notifier.show(gone))
    }

    /**
     * Приёмник действий не запускает активность ни при каком исходе: из уведомления это
     * запрещённый платформой переход (C1). Проверка читает манифест и класс приёмника — так
     * нарушение видно до того, как человек нажмёт кнопку и ничего не произойдёт.
     */
    @Test
    fun theActionReceiverNeverStartsAnActivity() {
        val source = NotificationActionReceiver::class.java.declaredMethods.map { it.name }
        assertTrue("startActivity в приёмнике", source.none { it.contains("openApp", ignoreCase = true) })

        // Спрашивать `queryBroadcastReceivers` по действию бесполезно: intent-фильтра у приёмника
        // нет, список приходит пустой, и «ни один не экспортирован» проходит всегда. Спрашиваем
        // сам компонент — он адресуется по имени, как его и зовёт уведомление.
        val declared = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, NotificationActionReceiver::class.java),
            0
        )
        assertFalse("приёмник действий объявлен наружу", declared.exported)
    }

    /**
     * Канал, который приложение перестало объявлять, система держит у себя дальше: у того, кто
     * ставил прежнюю сборку, в настройках остаётся переключатель, не способный ничего показать. Заведение каналов должно убирать за собой.
     */
    @Test
    fun aChannelWeNoLongerDeclareIsRemoved() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel("legacy", "Прежний канал", android.app.NotificationManager.IMPORTANCE_LOW)
        )

        NotificationChannels(context).ensure()

        assertEquals(null, manager.getNotificationChannel("legacy"))
    }

    /**
     * Канал выключен, а приложение — нет. Каналов у нас четыре, и человек выключает их по
     * отдельности (D8): он мог оставить сводку и запретить приёмы. `areNotificationsEnabled()`
     * отвечает про приложение и об этом не знает — `notify()` молчит, а мы пишем «сказано».
     */
    @Test
    fun aBlockedChannelIsNotAllowed() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val channel = NotificationChannel.EXPIRY.id
        try {
            manager.deleteNotificationChannel(channel)
            manager.createNotificationChannel(
                android.app.NotificationChannel(channel, "Сроки годности", android.app.NotificationManager.IMPORTANCE_NONE)
            )

            assertEquals(
                com.kert0n.medapp.domain.notification.Delivery.NOT_ALLOWED,
                notifier.show(planned)
            )
        } finally {
            manager.deleteNotificationChannel(channel)
            NotificationChannels(context).ensure()
        }
    }
}
