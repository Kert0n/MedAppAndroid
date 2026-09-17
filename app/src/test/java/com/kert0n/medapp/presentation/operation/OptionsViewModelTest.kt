package com.kert0n.medapp.presentation.operation

import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.domain.notification.Readiness
import com.kert0n.medapp.fixture.FakeAppLanguages
import com.kert0n.medapp.fixture.FakeSyncOperations
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.platform.settings.AppLanguage
import com.kert0n.medapp.platform.settings.CameraAccess
import com.kert0n.medapp.platform.settings.DevicePermissions
import com.kert0n.medapp.platform.settings.PermissionStates
import com.kert0n.medapp.presentation.settings.LanguageChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Место «Опции» (PLAN H3): одно состояние строк — очередь, разрешения, язык — от одного владельца. */
class OptionsViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private class System(var notifications: Boolean) : DevicePermissions, NotificationReadiness {
        override fun current() = PermissionStates(notifications, exactAlarms = true, camera = CameraAccess.GRANTED)
        override fun now() = Readiness(notifications, emptySet())
    }

    /**
     * Строка разрешений говорит о беде, пока её не починили, и гаснет по возвращении из настроек:
     * иначе «Что-то выключено» висело бы после починки до перезапуска.
     */
    @Test
    fun permissionTroubleIsReadAgainOnReturn() {
        val system = System(notifications = false)
        val model = OptionsViewModel(FakeSyncOperations(), system, system, FakeAppLanguages())

        watching(model.state) { state ->
            state.awaiting { it.permissionsTrouble }
            system.notifications = true
            model.refresh()
            state.awaiting { !it.permissionsTrouble }
        }
    }

    /** Строка языка называет выбранный в системе язык: иначе «Как в системе» стояло бы при английском. */
    @Test
    fun theLanguageRowNamesTheChosenLanguage() {
        val system = System(notifications = true)
        val model = OptionsViewModel(FakeSyncOperations(), system, system, FakeAppLanguages(AppLanguage.ENGLISH))

        val state = watching(model.state) { it.awaiting { s -> s.language == LanguageChoice.ENGLISH } }

        assertEquals(LanguageChoice.ENGLISH, state.language)
    }
}
