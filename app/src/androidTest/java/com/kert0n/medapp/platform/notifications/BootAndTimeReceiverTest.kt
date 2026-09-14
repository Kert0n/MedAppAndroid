package com.kert0n.medapp.platform.notifications

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Загрузка, перевод часов и смена зоны — проход дня сейчас; посторонний сигнал — ничего (PLAN D8). */
@RunWith(AndroidJUnit4::class)
class BootAndTimeReceiverTest {

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
