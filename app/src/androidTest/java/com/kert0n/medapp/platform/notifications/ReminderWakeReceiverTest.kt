package com.kert0n.medapp.platform.notifications

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.fixture.await
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Путь от будильника до показа — целиком, через настоящий широковещательный приём и настоящий граф.
 *
 * Проверка есть потому, что этот путь единственный, каким система нас будит, а закрыт он был только
 * прогонами `pass()` из кода теста: переименуй действие, забудь объявить приёмник в манифесте или
 * потеряй внедрение — весь набор остался бы зелёным, а напоминания перестали бы приходить вовсе.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReminderWakeReceiverTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var outbox: ReminderOutbox

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = hilt.inject()

    /**
     * Система разбудила — владелец доставки сходил. Считаем проходы по его состоянию: их число
     * растёт, а значит приёмник объявлен, найден по своему действию, получил внедрение и **дождался**
     * прохода, прежде чем отпустить процесс (`goAsync`).
     */
    @Test
    fun theAlarmReachesTheOutboxThroughTheRealBroadcast() {
        val before = outbox.state.value.passes

        context.sendBroadcast(Intent(context, ReminderWakeReceiver::class.java).setAction(ReminderWakeReceiver.ACTION))

        runBlocking { await("проход по будильнику") { outbox.state.value.passes > before } }
    }

    /** Посторонний сигнал проходом не оборачивается: приёмник отвечает только своему действию. */
    @Test
    fun aStrangerActionWakesNobody() {
        val before = outbox.state.value.passes

        // Защищённые системой действия слать нельзя, поэтому берём своё же, но чужое приёмнику.
        context.sendBroadcast(Intent(context, ReminderWakeReceiver::class.java).setAction("com.kert0n.medapp.SOMETHING_ELSE"))
        Thread.sleep(500)

        assertTrue("посторонний сигнал поднял проход", outbox.state.value.passes == before)
    }
}
