package com.kert0n.medapp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Прочитанное намерение **опустошается**: пересоздание окна (поворот, смена темы) читает тот же
 * `Intent`, и без этого человека возвращало бы туда, откуда он уже ушёл.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Намерение читается **до** вёрстки и ровно один раз: `setContent` зовётся заново при
        // каждой перерисовке, и чтение внутри него опустошало бы `Intent` по второму кругу.
        val opening = takeOpening(intent)
        setContent {
            var asked by remember { mutableStateOf(opening) }
            MedAppTheme {
                MedAppApp(opening = asked, onOpened = { asked = null })
            }
        }
    }

    /** Приложение уже открыто, и человек нажал на карточку: окно то же, а цель новая. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun takeOpening(intent: Intent): NotificationOpening? =
        NotificationTargetExtras.read(intent).also { intent.replaceExtras(null) }
}
