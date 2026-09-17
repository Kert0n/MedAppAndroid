package com.kert0n.medapp.ui.medkit

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Ключ приглашения узором (PLAN H3 №20, №21). Узор рисуется **чёрным по белому при любой теме**:
 * его читает камера чужого телефона, а не человек, и на тёмной подложке распознаватели его теряют.
 *
 * Считается один раз на ключ: узор от него и зависит, а размер на экране задаёт уже разметка.
 */
@Composable
fun rememberInvitationPattern(code: String): ImageBitmap = remember(code) { pattern(code) }

private const val SIDE = 640

private fun pattern(code: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        code,
        BarcodeFormat.QR_CODE,
        SIDE,
        SIDE,
        mapOf(
            // Поле вокруг узора нужно распознавателю; одного модуля хватает, а по умолчанию их
            // четыре, и узор на экране становится заметно мельче.
            EncodeHintType.MARGIN to 1,
            // Ключ короткий, места не жаль: чем выше уровень, тем легче узор читается с экрана,
            // который бликует и подрагивает в руках.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q
        )
    )
    val pixels = IntArray(matrix.width * matrix.height) { at ->
        if (matrix.get(at % matrix.width, at / matrix.width)) BLACK else WHITE
    }
    val bitmap = createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bitmap.asImageBitmap()
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
