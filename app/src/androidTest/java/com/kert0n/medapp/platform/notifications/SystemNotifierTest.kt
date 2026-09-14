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
     * **Разные уведомления не делят один `PendingIntent`.** Тождество намерения для системы —
     * код запроса и `filterEquals` намерения, а extras в него не входят. Пока код запроса был
     * `hashCode()` ключа, две коробки с совпавшим хешем получали одно системное намерение, и
     * `FLAG_UPDATE_CURRENT` подменял цель прежней карточки: нажал на первую — открылась вторая.
     * Полное имя цели входит в тождество (`setIdentifier`), и хеш больше ничего не решает.
     */
    @Test
    fun collidingKeysDoNotShareAPendingIntent() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // Два идентификатора, у которых `"<id>@2027-03-31".hashCode()` совпадает: найдены перебором.
        val first = kotlin.uuid.Uuid.parse("2f786c9c-a130-4658-beee-092331cf0eb8")
        val second = kotlin.uuid.Uuid.parse("21279b22-bef8-40db-9041-73678e446eaa")
        database.packageRepository().add(pack(id = first, name = "Первая", quantity = tablets("20"), expiresOn = expiry))
        database.packageRepository().add(pack(id = second, name = "Вторая", quantity = tablets("20"), expiresOn = expiry))
        val a = Reminder(NotificationKey.expiry(first, expiry, NotificationKind.EXPIRY_SOURCE_3D), NotificationTarget.PackageCard(first), planned.dueAt)
        val b = Reminder(NotificationKey.expiry(second, expiry, NotificationKind.EXPIRY_SOURCE_3D), NotificationTarget.PackageCard(second), planned.dueAt)
        assertEquals("пара перестала сталкиваться — проверка сторожила бы пустоту", a.key.hashCode(), b.key.hashCode())

        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SHOWN, notifier.show(a))
        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SHOWN, notifier.show(b))

        val shownA = requireNotNull(awaitShown(a.key.subject)) { "первая не показана" }
        val shownB = requireNotNull(awaitShown(b.key.subject)) { "вторая не показана" }
        org.junit.Assert.assertNotEquals(
            "две карточки делят одно системное намерение",
            shownA.notification.contentIntent, shownB.notification.contentIntent
        )
        notifier.dismiss(a.key)
        notifier.dismiss(b.key)
    }

    /**
     * «Принял» — намерение **открыть приложение**, «Пропустить» и «Отложить» — широковещание
     * приёмнику: первый требует экрана, вторым он не нужен. Из приёмника активность не открыть
     * (trampoline), поэтому кнопка и едет активностью — с целью и действием в extras.
     */
    @Test
    fun takeOpensTheAppWhileSkipAndSnoozeGoToTheReceiver() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // Текст напоминания собирается из пункта и курса, поэтому лечение заводится по-настоящему.
        val scenarios = com.kert0n.medapp.fixture.Scenarios(database, planned.dueAt)
        val tablets = kotlin.uuid.Uuid.random()
        database.packageRepository().add(pack(id = tablets, name = "Ибупрофен", quantity = tablets("20"), form = com.kert0n.medapp.fixture.TABLET_FORM))
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                com.kert0n.medapp.feature.course.CourseDrafting.Edit.SetDose(com.kert0n.medapp.fixture.dose("2")),
                com.kert0n.medapp.feature.course.CourseDrafting.Edit.SetForm(com.kert0n.medapp.fixture.TABLET_FORM),
                com.kert0n.medapp.feature.course.CourseDrafting.Edit.SetSchedule(com.kert0n.medapp.fixture.schedule(start = LocalDate.of(2027, 3, 28))),
                com.kert0n.medapp.feature.course.CourseDrafting.Edit.SetTotalDoses(com.kert0n.medapp.domain.value.Doses(3)),
                com.kert0n.medapp.feature.course.CourseDrafting.Edit.Attach(tablets, com.kert0n.medapp.domain.value.Doses(3))
            )
        ) as com.kert0n.medapp.feature.course.CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        val first = database.intakeRepository().ofCourse(saved.draft.id).filterIsInstance<com.kert0n.medapp.domain.intake.CourseIntake>().minBy { it.plannedAt }
        val reminder = Reminder(NotificationKey.intake(first.id, NotificationKind.INTAKE_DUE), NotificationTarget.Intake(first.id), planned.dueAt)

        assertEquals(com.kert0n.medapp.domain.notification.Delivery.SHOWN, notifier.show(reminder))

        val shown = requireNotNull(awaitShown(reminder.key.subject)) { "напоминание не показано" }
        val actions = shown.notification.actions.associateBy { it.title.toString() }
        assertEquals(setOf("Принял", "Пропустить", "Отложить"), actions.keys)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assertTrue("«Принял» не открывает приложение", actions.getValue("Принял").actionIntent.isActivity)
            assertTrue("«Пропустить» открывает активность — trampoline", actions.getValue("Пропустить").actionIntent.isBroadcast)
            assertTrue("«Отложить» открывает активность — trampoline", actions.getValue("Отложить").actionIntent.isBroadcast)
        }
        notifier.dismiss(reminder.key)
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
