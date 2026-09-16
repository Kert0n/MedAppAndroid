package com.kert0n.medapp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.kert0n.medapp.domain.notification.NotificationOpening
import com.kert0n.medapp.platform.notifications.NotificationTargetExtras
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Единственное окно приложения (PLAN H3): всё остальное — места внутри оболочки.
 *
 * Окно же и принимает намерение из шторки: цель кладёт `SystemNotifier`, читает её
 * [NotificationTargetExtras] — своего разбора extras здесь не пишется (разбор #29).
 *
 * Цель применяется **один раз** (PLAN C1 «Открытие из шторки — один раз»). Восстановленное окно
 * получает от системы исходное намерение с теми же extras — после поворота, смерти процесса,
 * запуска из недавних, — поэтому читается оно только у свежего окна и у нового намерения. Ждущая
 * цель, которую оболочка ещё не применила, переживает пересоздание в сохранённом состоянии.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Цель, которую оболочка ещё не применила; применила — `null`. */
    private val opening = mutableStateOf<NotificationOpening?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        opening.value = savedInstanceState?.getBundle(PENDING)?.let(::pendingOf)
            ?: NotificationTargetExtras.launchOpening(intent, windowRestored = savedInstanceState != null)
        setContent {
            MedAppTheme {
                MedAppApp(opening = opening.value, onOpened = { opening.value = null })
            }
        }
    }

    /**
     * Приложение уже открыто, и человек нажал на карточку: окно то же, а цель новая. Окно не
     * пересоздаётся — место, в котором человек был, остаётся под новой целью.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationTargetExtras.launchOpening(intent, windowRestored = false)?.let { opening.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pending = opening.value ?: return
        outState.putBundle(
            PENDING,
            Bundle().apply {
                for ((key, value) in NotificationTargetExtras.encode(pending.target, pending.action)) putString(key, value)
            }
        )
    }

    private fun pendingOf(saved: Bundle): NotificationOpening? =
        NotificationTargetExtras.decode(saved.keySet().mapNotNull { key -> saved.getString(key)?.let { key to it } }.toMap())

    private companion object {
        const val PENDING = "notification_opening_pending"
    }
}
