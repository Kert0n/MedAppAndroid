package com.kert0n.medapp.app.notifications

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.feature.notification.ReminderReadings
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.fixture.await
import com.kert0n.medapp.platform.notifications.AlarmManagerReminders
import com.kert0n.medapp.platform.time.TimeShifts
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    @Inject
    lateinit var shifts: TimeShifts

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
        // Начальный проход и готовность наблюдателя — до отсчёта, и дождаться их обязательно:
        // иначе запоздавший начальный проход засчитали бы за ответ приёмнику.
        runBlocking { await("наблюдатель встал и начальный проход прошёл") { outbox.ready.value && outbox.state.value.passes >= 1 } }
        val before = outbox.state.value.passes

        BootAndTimeReceiver().onReceive(context, Intent(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))

        runBlocking { await("владелец постановок разбужен") { outbox.state.value.passes > before } }
    }

    @Inject
    lateinit var promises: ReminderRecords

    @Inject
    lateinit var alarms: AlarmManagerReminders

    @After
    fun tearDown() = runBlocking {
        alarms.stopWaking(exact = true)
        alarms.stopWaking(exact = false)
    }

    /**
     * После загрузки будильников в системе нет, а в процессе, который она подняла ради этого сигнала,
     * кроме приёмника никого нет: цикла владельца доставки может не быть вовсе, и умереть процесс
     * вправе сразу, как приёмник вернётся. Поэтому приёмник ставит будильник сам — каждый повод
     * перестроить день, — а не будит цикл, которого может не оказаться.
     *
     * Красная проверка: приёмник только звал `runNow()` цикла; без запущенного цикла проход не
     * случался, и будильник к обещанному после загрузки не вставал.
     */
    @Test
    fun theReceiverArmsTheAlarmItselfWithoutTheLoop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val later = java.time.Instant.now().plusSeconds(3_600)
        val day = later.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        promises.saveAll(listOf(Reminder(NotificationKey.digest(day), NotificationTarget.DayPlan(day), later)))

        for (action in BootAndTimeReceiver.TRIGGERS) {
            alarms.stopWaking(exact = false)

            BootAndTimeReceiver().onReceive(context, Intent(action))

            await("будильник после «$action» не встал", timeoutMillis = 5_000) { alarms.isScheduled(exact = false) }
        }
    }

    /**
     * Будильники — не единственные, кому этот сигнал нужен: открытый экран ждёт границы суток и
     * после переезда ждёт не того момента. Узнать об этом сам он не может — спит до старой
     * полуночи.
     *
     * Красная проверка: не говорить ждущим — весть не приходит, и до старой полуночи человек
     * читает вчерашний день.
     */
    @Test
    fun theReceiverTellsThoseWaitingForTheDayBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Ухо подписывается до сигнала, а не после: сказанное в пустоту не ждёт слушателя.
        val heard = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(5_000) { shifts.signals.first() }
        }

        BootAndTimeReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertNotNull("ждущим границы дня не сказали, что она уехала", heard.await())
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
