package com.kert0n.medapp.tour

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.presentation.bootstrap.AppStartState
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.ui.bootstrap.SetupScreen
import com.kert0n.medapp.ui.medkit.MedKitSharingScreen
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.io.File
import java.time.LocalTime
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экраны, которые обход в оболочке снять не может: ключ приглашения закрыт `FLAG_SECURE`, и снимок
 * устройства выходит чёрным; первичная настройка живёт до оболочки, а «ключ утрачен» живьём не
 * воспроизвести без порчи Keystore. Рисуются те же составляющие в процессе проверки.
 */
@RunWith(AndroidJUnit4::class)
class InProcessTour {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun gate() {
        assumeTrue("обход экранов включается только -e tour true", InstrumentationRegistry.getArguments().getString("tour") == "true")
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MedAppTheme { content() } }
        compose.waitForIdle()
    }

    private fun save(name: String, inDialog: Boolean = false) {
        compose.waitForIdle()
        val image = (if (inDialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tour/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun setupChecking() {
        show { SetupScreen(AppStartState.Checking, onRetry = {}) }
        save("01-setup/checking")
    }

    @Test
    fun setupNoConnection() {
        show { SetupScreen(AppStartState.Setup(Unavailability.NO_CONNECTION), onRetry = {}) }
        save("01-setup/no-connection")
    }

    @Test
    fun setupKeyLost() {
        show { SetupScreen(AppStartState.KeyLost, onRetry = {}) }
        save("01-setup/key-lost")
        compose.onNodeWithText("Начать с новой учётной записью").performClick()
        save("01-setup/key-lost-confirm", inDialog = true)
    }

    private fun shared(isFullScreen: Boolean) = MedKitSharingUiState.Shared(
        name = "Дача",
        invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30)),
        isFullScreen = isFullScreen
    )

    @Test
    fun sharingShared() {
        show { MedKitSharingScreen(shared(isFullScreen = false), {}, {}, {}, {}, {}, {}, {}) }
        save("20-sharing/shared")
    }

    @Test
    fun invitationFullScreen() {
        show { MedKitSharingScreen(shared(isFullScreen = true), {}, {}, {}, {}, {}, {}, {}) }
        save("21-invitation/full-screen", inDialog = true)
    }
}
