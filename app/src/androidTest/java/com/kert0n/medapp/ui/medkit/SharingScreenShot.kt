package com.kert0n.medapp.ui.medkit

import android.graphics.Bitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.io.File
import java.time.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает экран «Поделиться» **из процесса проверки**: на устройстве он закрыт `FLAG_SECURE`, и
 * `screencap` отдаёт чёрный кадр. Рисуется здесь то же самое, что человек видит там.
 *
 * Это не проверка, а инструмент показа: в наборе проверок ей ничего не утверждается.
 */
@RunWith(AndroidJUnit4::class)
class SharingScreenShot {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: MedKitSharingUiState, inDialog: Boolean = false) {
        compose.setContent {
            MedAppTheme {
                MedKitSharingScreen(
                    state = state,
                    onAsk = {}, onDismissAsking = {}, onPublish = {}, onInvite = {},
                    onShowFullScreen = {}, onHideFullScreen = {}, onBack = {}
                )
            }
        }
        // Диалог живёт в своём окне: корень активности снимет из-под него пустоту.
        val node = if (inDialog) compose.onNode(isDialog()) else compose.onRoot()
        val image = node.captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots")
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("снимок $name не получился", file.length() > 0)
    }

    @Test
    fun deciding() = shot("20-deciding", MedKitSharingUiState.Deciding("Домашняя"))

    @Test
    fun fullScreen() = shot(
        "21-full-screen",
        MedKitSharingUiState.Shared(
            name = "Семейная",
            invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30)),
            isFullScreen = true
        ),
        inDialog = true
    )

    @Test
    fun shared() = shot(
        "20-shared",
        MedKitSharingUiState.Shared(
            name = "Семейная",
            invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30))
        )
    )
}
