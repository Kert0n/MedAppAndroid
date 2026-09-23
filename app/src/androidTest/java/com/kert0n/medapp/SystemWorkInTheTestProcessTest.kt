package com.kert0n.medapp

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверки живут в процессе приложения, а на устройстве стоит настоящая установка: её ежечасный
 * заход (`SyncWorker`) система держит в своём расписании и отдаёт процессу, когда подходит срок, —
 * не спрашивая, приложение там сейчас или прогон. Процесс проверок такое задание принимает и
 * отпускает, а не падает: иначе прогон, на который пришёлся срок, обрывается на полуслове с
 * «WorkManager needs to be initialized», и каждая следующая проверка не запускается вовсе.
 *
 * Проверка ни на какой граф не опирается и гонится **одна** (`class=`): так она попадает в
 * свежий процесс, где до неё никто ничего не настраивал, — ровно туда, куда приходит задание.
 */
@RunWith(AndroidJUnit4::class)
class SystemWorkInTheTestProcessTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @After
    fun tearDown() = jobs.cancel(JOB)

    @Test
    fun aJobTheInstalledAppLeftScheduledIsTakenAndLetGo() {
        // Так задание кладёт в расписание WorkManager самого приложения: служба его и номер работы.
        val extras = PersistableBundle().apply { putString("EXTRA_WORK_SPEC_ID", "работа прежней установки") }
        jobs.schedule(
            JobInfo.Builder(JOB, ComponentName(context.packageName, WORK_MANAGER_JOBS))
                .setExtras(extras)
                .setOverrideDeadline(DEADLINE_MS)
                .build()
        )
        instrumentation.uiAutomation.executeShellCommand("cmd jobscheduler run -f ${context.packageName} $JOB").close()

        val until = SystemClock.uptimeMillis() + WAIT_MS
        while (jobs.getPendingJob(JOB) != null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        assertNull("задание так и не отпущено процессом проверок", jobs.getPendingJob(JOB))
    }

    private companion object {
        /** Номер вне тех, что раздаёт WorkManager: они считаются от нуля. */
        const val JOB = 7_300_001

        /** Служба, через которую WorkManager получает задания системы, — из манифеста приложения. */
        const val WORK_MANAGER_JOBS = "androidx.work.impl.background.systemjob.SystemJobService"
        const val DEADLINE_MS = 60_000L
        const val WAIT_MS = 10_000L
    }
}
