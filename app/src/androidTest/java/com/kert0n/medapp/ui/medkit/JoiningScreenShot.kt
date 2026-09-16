package com.kert0n.medapp.ui.medkit

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.presentation.medkit.MedKitJoiningRefusal
import com.kert0n.medapp.presentation.medkit.MedKitJoiningUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Показ экрана 22, чтобы на него можно было посмотреть. Ничего не утверждает. */
@RunWith(AndroidJUnit4::class)
class JoiningScreenShot {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun joining() {
        compose.setContent {
            MedAppTheme {
                MedKitJoiningScreen(
                    state = MedKitJoiningUiState(code = "K7F-2M9-QX4", refusal = MedKitJoiningRefusal.Invalid),
                    onType = {}, onJoin = {}, onBack = {}
                )
            }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots")
        dir.mkdirs()
        val file = File(dir, "22-joining.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("снимок не получился", file.length() > 0)
    }
}
