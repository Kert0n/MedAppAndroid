package com.kert0n.medapp.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R

/**
 * Полоса доли: подпись, число и сама полоса длиной в долю целого.
 *
 * **Число стоит текстом всегда**: длина и цвет — не единственные носители (PLAN H3 «Дизайн»), а
 * человеку, читающему экран на слух, полоса не говорит ничего. Поэтому сама полоса для чтеца
 * молчит — всё, что она показывает, уже сказано подписью и числом рядом.
 *
 * Рисуется своим прямоугольником, а не `LinearProgressIndicator`: тот значит «идёт работа», и
 * экранный чтец объявил бы диаграмму прогрессом.
 */
@Composable
fun ShareBar(
    label: String,
    value: String,
    share: Float,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val text = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val row = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Column(
        // Строка — **одна** единица чтения: подпись, число и пояснение читаются вместе, а не
        // тремя отдельными кусками, и вместе с ними чтец называет нажатие, когда оно есть.
        row.semantics(mergeDescendants = true) { }
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = text, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = text)
            // Стрелка — признак того, что строка ведёт дальше (PLAN H3 «Дизайн»). Чтецу она молчит:
            // нажатие он находит по самой строке, а не по значку внутри неё.
            if (onClick != null) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Bar(share = share, color = color, dimmed = dimmed)
    }
}

/**
 * Сама полоса. Ширина берётся долей от доступной, а не `fillMaxWidth(share)`: у нулевой доли
 * должна остаться видимая подложка, а у доли меньше пикселя — хоть что-то, иначе строка выглядит
 * как ошибка рисования.
 */
@Composable
private fun Bar(share: Float, color: Color, dimmed: Boolean) {
    val filled = if (dimmed) color.copy(alpha = 0.45f) else color
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics { }
    ) {
        Layout(
            content = { Box(Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraSmall).background(filled)) },
            measurePolicy = { measurables, constraints ->
                val width = (constraints.maxWidth * share.coerceIn(0f, 1f)).toInt().coerceAtLeast(if (share > 0f) 2 else 0)
                val placeable = measurables.first().measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
            }
        )
    }
}
