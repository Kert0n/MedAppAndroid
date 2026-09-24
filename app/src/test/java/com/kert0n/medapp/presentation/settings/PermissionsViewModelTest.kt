package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.domain.notification.NotificationChannel
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.Readiness
import com.kert0n.medapp.feature.settings.CameraAccess
import com.kert0n.medapp.feature.settings.DevicePermissions
import com.kert0n.medapp.feature.settings.PermissionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разрешения (PLAN H3 №27): состояние читается у системы и перечитывается по возвращении, а
 * своего мнения о нём экран не держит. На JVM версии Android нет (`SDK_INT = 0`), поэтому
 * точность будильников здесь всегда «не требуется» — её ветви проверяет `PermissionsScreenTest`.
 */
class PermissionsViewModelTest {

    /** Система, ответ которой проверка меняет между чтениями — как человек в её настройках. */
    private class System(
        var notifications: Boolean = true,
        var muted: Set<NotificationChannel> = emptySet(),
        var camera: CameraAccess = CameraAccess.GRANTED
    ) : DevicePermissions, NotificationReadiness {
        override fun current() = PermissionStates(notifications, exactAlarms = true, camera = camera)
        override fun now() = Readiness(notifications, muted)
    }

    /** Всё разрешённое — не беда: иначе строка «Разрешения» в «Опциях» звала бы чинить то, что не сломано. */
    @Test
    fun everythingAllowedIsNoTrouble() {
        val model = PermissionsViewModel(System(), System())

        assertFalse(model.state.value.hasTrouble)
        assertEquals(PermissionState.GRANTED, model.state.value.notifications)
        assertEquals(PermissionState.GRANTED, model.state.value.camera)
    }

    /** Отозванное — беда, и она названа именно у своей строки. */
    @Test
    fun aRevokedPermissionIsTroubleOnItsOwnRow() {
        val system = System(notifications = false)
        val model = PermissionsViewModel(system, system)

        assertEquals(PermissionState.DENIED, model.state.value.notifications)
        assertEquals(PermissionState.GRANTED, model.state.value.camera)
        assertTrue(model.state.value.hasTrouble)
    }

    /**
     * Человек ушёл в системные настройки и вернулся: [PermissionsViewModel.refresh] читает
     * заново, и починенное перестаёт быть бедой без пересоздания экрана.
     */
    @Test
    fun comingBackFromSystemSettingsRereadsTheState() {
        val system = System(notifications = false)
        val model = PermissionsViewModel(system, system)
        assertTrue(model.state.value.hasTrouble)

        system.notifications = true
        model.refresh()

        assertFalse(model.state.value.hasTrouble)
    }

    /** Заглушённый канал «Приёмы» — свой случай: разрешено, а напоминаний нет, и чинится иначе. */
    @Test
    fun aMutedIntakesChannelIsItsOwnCase() {
        val system = System(muted = setOf(NotificationChannel.INTAKES))
        val model = PermissionsViewModel(system, system)

        assertEquals(PermissionState.MUTED, model.state.value.notifications)
        assertTrue(model.state.value.hasTrouble)
    }

    /** Камеры нет — это не беда и чинить нечего: коробки заводятся на полке (T-45). */
    @Test
    fun anAbsentCameraIsNotTrouble() {
        val system = System(camera = CameraAccess.ABSENT)
        val model = PermissionsViewModel(system, system)

        assertEquals(PermissionState.ABSENT, model.state.value.camera)
        assertFalse(model.state.value.hasTrouble)
        assertFalse(model.state.value.camera.isFixable)
    }
}
