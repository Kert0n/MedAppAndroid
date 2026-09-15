package com.kert0n.medapp.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import com.kert0n.medapp.platform.background.SyncWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.kert0n.medapp.di.DefaultDispatcher
import com.kert0n.medapp.di.IoDispatcher
import com.kert0n.medapp.di.MainDispatcher

/**
 * Внедрение работает от Application до экрана: граф поднимается на настоящем `MedApp`,
 * выдаёт диспетчеры и доживает до запущенной `MainActivity`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    @Inject
    @DefaultDispatcher
    lateinit var computation: CoroutineDispatcher

    @Inject
    @MainDispatcher
    lateinit var main: CoroutineDispatcher

    @Test
    fun applicationIsHiltApplication() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("com.kert0n.medapp", (application as android.app.Application).packageName)
    }

    @Test
    fun graphProvidesDispatchers() {
        hilt.inject()
        assertNotNull(io)
        assertNotNull(computation)
        assertNotNull(main)
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Фоновую задачу система создаёт по имени класса, и собрать её может только фабрика графа: без
     * неё `SyncWorker` не получил бы координатор синхронизации (PLAN E4).
     */
    @Test
    fun graphBuildsTheSyncWorker() {
        hilt.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val worker = TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(workerFactory).build()

        assertNotNull("граф не собрал работника", worker)
    }

    @Test
    fun mainActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(scenario.state)
        }
    }
}
