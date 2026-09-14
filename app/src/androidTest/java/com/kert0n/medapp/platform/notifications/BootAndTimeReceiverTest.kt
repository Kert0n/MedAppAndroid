package com.kert0n.medapp.platform.notifications

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.feature.notification.ReminderOutbox
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Загрузка, перевод часов, смена зоны и возврат точных будильников — проход дня **и** проход
 * владельца постановок сейчас; посторонний сигнал — ничего (PLAN D8). Сверка без изменений ничего
 * не пишет и владельца доставки не разбудит, а будильники в системе — его.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BootAndTimeReceiverTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var outbox: ReminderOutbox

    @Before
    fun setUp() {
        hilt.inject()
        // Проход дня — задача планировщика; в тестовом приложении его надо поднять самим.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            InstrumentationRegistry.getInstrumentation().targetContext,
            androidx.work.Configuration.Builder().build()
        )
    }

    /** Системные сигналы слать нельзя — приёмник зовётся прямо, как позвала бы система. */
    @Test
    fun theReceiverWakesTheOwnerOfTheAlarms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        outbox.start()
        // Начальный проход и готовность наблюдателя — до отсчёта: иначе их и засчитали бы за ответ.
        val started = System.currentTimeMillis() + 5_000
        while (!(outbox.ready.value && outbox.state.value.passes >= 1) && System.currentTimeMillis() < started) Thread.sleep(50)
        Thread.sleep(300)
        val before = outbox.state.value.passes

        BootAndTimeReceiver().onReceive(context, Intent(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))

        val deadline = System.currentTimeMillis() + 5_000
        while (outbox.state.value.passes <= before && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("владелец постановок не разбужен", outbox.state.value.passes > before)
    }

    @Test
    fun eachTriggerRestartsTheDayAndStrangersDoNot() {
        for (action in BootAndTimeReceiver.TRIGGERS) assertTrue(action, BootAndTimeReceiver.restartsTheDay(action))
        assertFalse(BootAndTimeReceiver.restartsTheDay(Intent.ACTION_BATTERY_LOW))
        assertFalse(BootAndTimeReceiver.restartsTheDay(null))
    }

    /** Манифест слушает ровно эти сигналы: приёмник объявлен и отвечает на каждый. */
    @Test
    fun theManifestDeclaresEveryTrigger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (action in BootAndTimeReceiver.TRIGGERS) {
            val intent = Intent(action).setPackage(context.packageName)
            val receivers = context.packageManager.queryBroadcastReceivers(intent, 0).map { it.activityInfo.name }
            assertEquals(action, listOf(BootAndTimeReceiver::class.java.name), receivers.filter { it == BootAndTimeReceiver::class.java.name })
        }
    }
}
