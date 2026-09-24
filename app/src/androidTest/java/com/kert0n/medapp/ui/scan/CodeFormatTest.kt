package com.kert0n.medapp.ui.scan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.kert0n.medapp.domain.scan.CodeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Формат называет распознаватель, а не длина строки (PLAN H5) — и проверяется это **настоящим**
 * распознавателем по настоящей картинке кода: подделка здесь проверяла бы нашу веру в ML Kit, а
 * камеру эмулятора на код не навести.
 *
 * Картинки рисует ZXing, который и так лежит в сборке ради QR приглашения.
 */
@RunWith(AndroidJUnit4::class)
class CodeFormatTest {

    private fun drawn(format: BarcodeFormat, text: String): Bitmap {
        val matrix = MultiFormatWriter().encode(text, format, SIDE, SIDE, mapOf(EncodeHintType.MARGIN to MARGIN))
        val image = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return image
    }

    /** Что увидел бы наш разбор кадра, окажись эта картинка перед камерой. */
    private fun read(format: BarcodeFormat, text: String): Pair<CodeFormat, String>? {
        val scanner = BarcodeScanning.getClient()
        try {
            val found = Tasks.await(scanner.process(InputImage.fromBitmap(drawn(format, text), 0)))
            val code = found.firstOrNull() ?: return null
            return codeFormat(code.format) to code.rawValue.orEmpty()
        } finally {
            scanner.close()
        }
    }

    /**
     * Код с упаковки узнаётся DataMatrix и приходит **той же строкой**, какой нарисован: на
     * `displayValue` вместо `rawValue` мы потеряли бы значащие знаки и спросили бы реестр о другой
     * коробке (PLAN H5).
     *
     * Разделителей GS в нарисованном коде нет, и это ограничение **рисовалки**, а не разбора:
     * ZXing рисует обычный DataMatrix без FNC1, а такой символ не читает и сам ML Kit. Настоящий
     * код реестра маркировки — GS1 с FNC1, и то, что он уходит в реестр байт в байт, держит
     * `MarkingCheckRequestNetworkDTOTest`.
     */
    @Test
    fun aDataMatrixIsReadAsItIsPrinted() {
        val text = "010460123456789021512345"

        assertEquals(CodeFormat.DATA_MATRIX to text, read(BarcodeFormat.DATA_MATRIX, text))
    }

    /** QR — это приглашение в аптечку, и путь у него свой. */
    @Test
    fun aQrCodeIsToldApartFromAPackageCode() {
        assertEquals(CodeFormat.QR to "K7F-2M9-QX4", read(BarcodeFormat.QR_CODE, "K7F-2M9-QX4"))
    }

    /**
     * EAN-13 описывает товарную позицию, а не эту коробку (PLAN C1 «Сканирование кодов»): он
     * узнаётся, но называется чужим — молчание было бы неотличимо от сломанной камеры.
     */
    @Test
    fun anEanIsRecognisedAndCalledAlien() {
        assertEquals(CodeFormat.OTHER to "4607004891014", read(BarcodeFormat.EAN_13, "4607004891014"))
    }

    private companion object {
        /**
         * Модуль кода на картинке должен быть соразмерен кадру: нарисованный вдесятеро крупнее,
         * тот же самый код ML Kit уже не находит (проба на устройстве). Камера держит это сама —
         * коробку в руке не увеличишь, — а нарисованному коду размер называют.
         */
        const val SIDE = 200
        const val MARGIN = 4
    }
}
