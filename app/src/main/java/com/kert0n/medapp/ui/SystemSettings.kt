package com.kert0n.medapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Переходы в системные настройки: разрешения приложения меняет человек у системы, а не у нас
 * (PLAN H3 №27, «Уведомления на экране»). Приложение только приводит его туда, где это делается.
 *
 * Своего состояния эти переходы не заводят: вернувшись, экран спрашивает систему заново.
 */
fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        // Настройки живут своей задачей: иначе «назад» из них возвращало бы не в приложение.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/**
 * Точные будильники **не просят диалогом** — Android отводит для них свой экран настроек
 * (`SCHEDULE_EXACT_ALARM`, Android 12+). До него разрешения не существует, и вести некуда.
 */
fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/**
 * Страница приложения в системных настройках: разрешение на камеру система выдаёт диалогом,
 * а отозванное чинится только там — своего экрана у камеры, в отличие от уведомлений, нет.
 */
fun Context.openAppDetailsSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
