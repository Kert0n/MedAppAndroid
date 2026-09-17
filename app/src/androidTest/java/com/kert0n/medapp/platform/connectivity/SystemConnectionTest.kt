package com.kert0n.medapp.platform.connectivity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.fixture.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Связь по словам системы (PLAN E4): у эмулятора её отнимают так же, как у живых проб, —
 * `svc data` и `svc wifi`, — и порт обязан это услышать, а вернувшуюся связь — услышать снова.
 *
 * Связь возвращается в `finally` и дожидается: следующий класс прогона живёт с сетью.
 */
@RunWith(AndroidJUnit4::class)
class SystemConnectionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    @Test
    fun takenAwayConnectionIsHeardAndSoIsItsReturn() = runBlocking {
        val connection = SystemConnection(instrumentation.targetContext)
        assertTrue("эмулятор начинает прогон без связи — проверять нечего", connection.online.value)
        try {
            shell("svc data disable")
            shell("svc wifi disable")
            await("связь пропала", timeoutMillis = 30_000) { !connection.online.value }
        } finally {
            shell("svc wifi enable")
            shell("svc data enable")
            await("связь вернулась", timeoutMillis = 60_000) { connection.online.value }
        }
    }
}
