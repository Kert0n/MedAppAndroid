package com.kert0n.medapp.platform.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.fixture.FakeReminders
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Состояние разрешений — то, что система говорит о приложении, а не что мы о ней думаем:
 * уведомления и камера читаются у неё, точность будильников — у их владельца.
 */
class AndroidDevicePermissionsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun statesComeFromTheSystem() {
        val states = AndroidDevicePermissions(context, FakeReminders(canBeExact = true)).current()

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
        assertEquals(true, AndroidDevicePermissions(context, FakeReminders(canBeExact = true)).current().exactAlarms)
        assertEquals(false, AndroidDevicePermissions(context, FakeReminders(canBeExact = false)).current().exactAlarms)
    }

    /** Отозванное разрешение на камеру видно как отказ, а не как отсутствие камеры. */
    @Test
    fun aRevokedCameraIsDeniedNotAbsent() {
        org.junit.Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        InstrumentationRegistry.getInstrumentation().uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assertEquals(CameraAccess.DENIED, AndroidDevicePermissions(context, FakeReminders()).current().camera)

        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assertEquals(CameraAccess.GRANTED, AndroidDevicePermissions(context, FakeReminders()).current().camera)
    }
}
