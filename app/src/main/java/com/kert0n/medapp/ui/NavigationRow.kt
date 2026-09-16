package com.kert0n.medapp.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.kert0n.medapp.R

/**
 * Переход на другой экран: значок, слова и стрелка вправо.
 *
 * Текстовой кнопкой такие переходы читались как заголовки — человек не догадывался, что по ним
 * можно нажать (замечание владельца 2026-09-16). Material 3 отводит для перехода строку списка:
 * ведущий значок называет, о чём она, завершающая стрелка говорит, что она ведёт дальше. Нажимается
 * строка целиком — цель ниже 48 dp не бывает.
 *
 * Действие **на месте** — принять, пересчитать — остаётся кнопкой: она не уводит, и стрелка ей
 * соврала бы.
 */
@Composable
fun NavigationRow(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = color) },
        // Стрелка молчит для экранного чтеца: она говорит глазу то же, что строке говорит нажатие.
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}
