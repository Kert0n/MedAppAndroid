package com.kert0n.medapp.platform.settings

import com.kert0n.medapp.fixture.FakeAppLanguages
import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.fixture.FakeReminders
import org.junit.Assume.assumeTrue
import com.kert0n.medapp.platform.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Состояние разрешений — то, что система говорит о приложении, а не что мы о ней думаем:
 * уведомления и камера читаются у неё, точность будильников — у их владельца.
 */
class AndroidDevicePermissionsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Состояние разрешений — системы, а не догадка: иначе строка «Разрешения» врала бы после отзыва в настройках. */
    @Test
    fun statesComeFromTheSystem() {
        val states = AndroidDevicePermissions(context, FakeReminders(canBeExact = true), NotificationChannels(context, FakeAppLanguages())).current()

        assertEquals(NotificationManagerCompat.from(context).areNotificationsEnabled(), states.notifications)
        val expectedCamera = when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> CameraAccess.ABSENT
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> CameraAccess.GRANTED
            else -> CameraAccess.DENIED
        }
        assertEquals(expectedCamera, states.camera)
    }

    /** Точность — ответ владельца будильников, и только его: при отказе экран показывает «приблизительно». */
    @Test
    fun exactnessIsTheAlarmOwnersAnswer() {
        assertEquals(true, AndroidDevicePermissions(context, FakeReminders(canBeExact = true), NotificationChannels(context, FakeAppLanguages())).current().exactAlarms)
        assertEquals(false, AndroidDevicePermissions(context, FakeReminders(canBeExact = false), NotificationChannels(context, FakeAppLanguages())).current().exactAlarms)
    }

    /**
     * Отказ в камере виден как отказ, а не как отсутствие камеры. Отзывать разрешение по-настоящему
     * в проверке нельзя: система убивает процесс с отозванным разрешением, а с ним и прогон
     * (разбор #29) — поэтому отказ отвечает обёртка контекста, а не система.
     */
    @Test
    fun aDeniedCameraIsDeniedNotAbsent() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        // Выдача процесс не трогает; так система отвечает «дано», а обёртка — «отказано».
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val denying = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.CAMERA) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
        }

        assertEquals(CameraAccess.DENIED, AndroidDevicePermissions(denying, FakeReminders(), NotificationChannels(denying, FakeAppLanguages())).current().camera)
        assertEquals(CameraAccess.GRANTED, AndroidDevicePermissions(context, FakeReminders(), NotificationChannels(context, FakeAppLanguages())).current().camera)
    }
}
