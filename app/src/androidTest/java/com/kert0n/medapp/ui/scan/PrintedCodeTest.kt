package com.kert0n.medapp.ui.scan

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Настоящий код с настоящей коробки, снятый телефоном (снимки владельца 2026-09-17). Нарисованным
 * кодом этого не проверить: ZXing рисует DataMatrix **без** FNC1, а «Честный знак» печатает GS1 —
 * с признаком в начале и разделителями GS между полями.
 *
 * Здесь держатся два обещания разбора. Внутренние разделители доезжают до нас **как напечатаны**:
 * возьми мы `displayValue` вместо `rawValue`, они исчезли бы, и в реестр ушёл бы другой код. А
 * ведущий признак FNC1 снимается: тот же признак сетевая граница дописывает текстом `{FNC1}`, и
 * оставленный здесь он удвоил бы начало кода (PLAN H5).
 */
@RunWith(AndroidJUnit4::class)
class PrintedCodeTest {

    private fun read(asset: String): ScannedCode {
        val context = InstrumentationRegistry.getInstrumentation().context
        val photo = context.assets.open("scan/$asset").use { BitmapFactory.decodeStream(it) }
        val scanner = BarcodeScanning.getClient()
        try {
            val found = Tasks.await(scanner.process(InputImage.fromBitmap(photo, 0)))
            val code = found.firstOrNull() ?: error("на снимке $asset код не распознан")
            return code.scanned() ?: error("на снимке $asset код пустой")
        } finally {
            scanner.close()
        }
    }

    /** Снимок коробки «Уголь активированный» — той самой, с которой снята фикстура CRPT. */
    @Test
    fun aPrintedCodeIsReadWithItsSeparators() {
        val code = read("charcoal.jpg")

        assertEquals(CodeFormat.DATA_MATRIX, code.format)
        assertTrue("код начинается с GTIN, а не с признака FNC1: ${code.text}", code.text.startsWith("01"))
        assertTrue("разделители между полями на месте", code.text.contains(GS))
    }

    /**
     * Коробку держат как держат, и код на снимке лежит боком: поворот распознаватель разбирает
     * сам. Требовать от человека ровного кадра значило бы требовать того, чего он не умеет.
     */
    @Test
    fun aCodeIsReadSideways() {
        val code = read("sideways.jpg")

        assertEquals(CodeFormat.DATA_MATRIX, code.format)
        assertTrue("код начинается с GTIN: ${code.text}", code.text.startsWith("01"))
    }

    private companion object {
        const val GS = '\u001D'
    }
}
