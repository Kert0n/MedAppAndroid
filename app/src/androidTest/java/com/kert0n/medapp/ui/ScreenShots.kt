package com.kert0n.medapp.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Рисует экраны в **своём** процессе и складывает PNG. Нужен по двум причинам: экраны с ключом
 * приглашения закрыты `FLAG_SECURE`, и `screencap` отдаёт с них чёрный кадр; а состояния вроде
 * «удаление в пути» или отвергнутой операции руками воспроизводятся долго и не всегда.
 *
 * Это не проверка поведения — утверждается только то, что снимок получился. Куда класть готовые,
 * сказано в `docs/screens/README.md`.
 */
@RunWith(AndroidJUnit4::class)
abstract class ScreenShots {

    @get:Rule
    val compose = createComposeRule()

    /** Снимок корня экрана. */
    protected fun shot(name: String, content: @Composable () -> Unit) = take(name, inDialog = false, content)

    /** Снимок окна поверх экрана: у диалога и листа своё окно, и корень снимет из-под него пустоту. */
    protected fun shotOfDialog(name: String, content: @Composable () -> Unit) = take(name, inDialog = true, content)

    private fun take(name: String, inDialog: Boolean, content: @Composable () -> Unit) {
        compose.setContent { MedAppTheme { content() } }
        compose.waitForIdle()
        val node = if (inDialog) compose.onNode(isDialog()) else compose.onRoot()
        val image = node.captureToImage().asAndroidBitmap()
        // Имя несёт и папку экрана («20-sharing/local»), поэтому заводятся все уровни, а не один.
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("снимок $name не получился", file.length() > 0)
    }
}
