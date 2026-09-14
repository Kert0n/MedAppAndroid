package com.kert0n.medapp

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Инструментальные тесты поднимают граф Hilt, а не настоящий `MedApp`.
 *
 * Системный сигнал может прийти в процесс проверок **до первого теста**: переустановка меняет
 * состояние разрешения на точные будильники, и `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`
 * будит `BootAndTimeReceiver`, когда графа ещё нет (`HiltAndroidRule` не отработал). В приложении
 * граф есть с `MedApp.onCreate`, и будить есть кого; в процессе проверок будить некого — сигнал
 * не роняет прогон (иначе он кончался нулём тестов), а называется в журнале.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onException(obj: Any?, e: Throwable): Boolean {
        if (obj is BroadcastReceiver && e.isMissingTestComponent()) {
            Log.w("HiltTestRunner", "системный сигнал ${obj::class.java.simpleName} до первого теста: графа ещё нет, будить некого")
            return true
        }
        return super.onException(obj, e)
    }

    private fun Throwable.isMissingTestComponent(): Boolean =
        generateSequence(this) { it.cause }.any { it is IllegalStateException && it.message?.contains("component was not created") == true }
}
