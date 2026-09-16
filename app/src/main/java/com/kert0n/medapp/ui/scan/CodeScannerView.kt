package com.kert0n.medapp.ui.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode

/**
 * Живая картинка камеры, которая называет увиденные коды (PLAN H3 «Набор сканера»).
 *
 * **Камера открывается и закрывается по жизненному циклу владельца**, а не по нажатиям: ушёл
 * человек с места — картинка гаснет и объектив освобождается, вернулся — включается снова.
 * Камера одна на устройство, и оставленная включённой она не даётся ни другому приложению, ни
 * второму входу в этот же экран.
 *
 * **Формат называет распознаватель**, а не длина строки ([codeFormat]): DataMatrix «Честного
 * знака» и QR приглашения различаются символикой. Распознаются при этом **все** форматы, а не
 * только два нужных: увиденный EAN-13 надо назвать словами, а не промолчать о нём, — иначе
 * человек будет держать коробку перед телефоном и гадать, что не так.
 *
 * Текст берётся `rawValue`, а не `displayValue`: в DataMatrix между полями стоят разделители GS,
 * и «читаемый» вид срезал бы значащие знаки, а код уходит в реестр байт в байт (PLAN H5).
 */
@Composable
fun CodeScannerView(onCode: (ScannedCode) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Свежее действие в уже привязанном распознавателе: пересобирать его на каждую перерисовку
    // значило бы гасить и зажигать камеру.
    val heard = rememberUpdatedState(onCode)
    val controller = remember(context) { LifecycleCameraController(context) }
    DisposableEffect(controller, owner) {
        val scanner = BarcodeScanning.getClient()
        // Снимков и записи видео у сканера нет — только разбор кадров: лишние способы съёмки
        // занимают память и греют телефон впустую.
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(context), CodeReader(scanner, heard))
        controller.bindToLifecycle(owner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { made -> PreviewView(made).also { it.controller = controller } },
        modifier = modifier
    )
}

/**
 * Разбор кадра. Сам разбор идёт внутри ML Kit на своём потоке, и главный поток тратится только
 * на то, чтобы отдать ему кадр; кадр закрывается в любом исходе — не закрытый останавливает
 * поток кадров целиком.
 */
private class CodeReader(
    private val scanner: BarcodeScanner,
    private val onCode: State<(ScannedCode) -> Unit>
) : ImageAnalysis.Analyzer {

    @OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        val frame = image.image
        if (frame == null) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(frame, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { found -> found.firstNotNullOfOrNull { it.scanned() }?.let(onCode.value) }
            .addOnCompleteListener { image.close() }
    }
}

/** Пустой код кодом не является: показывать о нём нечего и спрашивать о нём нечего. */
private fun Barcode.scanned(): ScannedCode? =
    rawValue?.takeIf { it.isNotEmpty() }?.let { ScannedCode(codeFormat(format), it) }

/** Что за код перед камерой — словами домена. Всё, кроме двух знакомых форматов, — чужое. */
internal fun codeFormat(format: Int): CodeFormat = when (format) {
    Barcode.FORMAT_DATA_MATRIX -> CodeFormat.DATA_MATRIX
    Barcode.FORMAT_QR_CODE -> CodeFormat.QR
    else -> CodeFormat.OTHER
}
