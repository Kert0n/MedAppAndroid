package com.kert0n.medapp

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Пустое окно для проверок экрана: рисует то, что даст тест, и умеет отдавать ViewModel из графа
 * Hilt. Живёт в `src/debug`, а не в тестах: активность поднимается в **проверяемом** приложении,
 * и класса из `androidTest` там нет. В распространяемую сборку она не попадает.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
