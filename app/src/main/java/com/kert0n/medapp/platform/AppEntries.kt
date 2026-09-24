package com.kert0n.medapp.platform

import android.content.BroadcastReceiver
import androidx.work.ListenableWorker

/**
 * Входы приложения, в которые платформа направляет систему: кого будит расписание, кому едет
 * будильник и кнопка в шторке. Платформа умеет будить, а кого — называет сборка: входы живут в
 * `app`, и платформе они не видны.
 */
class AppEntries(
    val sync: Class<out ListenableWorker>,
    val daily: Class<out ListenableWorker>,
    val reminderWake: Class<out BroadcastReceiver>,
    val notificationAction: Class<out BroadcastReceiver>
)
