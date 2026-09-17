package com.kert0n.medapp.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.getSystemService

/**
 * Кладёт в буфер обмена **секрет** (PLAN G3): ключ приглашения открывает чужую аптечку, поэтому
 * система помечается о том, что показывать его в своей подсказке не нужно. До Android 13 такой
 * пометки не существует, и там она просто не ставится — большего клиент сделать не может.
 *
 * Метка видна человеку в истории буфера, поэтому она называет **что** это, а не значение.
 */
fun Context.copySecret(label: String, secret: String) {
    val clip = ClipData.newPlainText(label, secret)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    getSystemService<ClipboardManager>()?.setPrimaryClip(clip)
}
