package com.kert0n.medapp.ui

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Что уезжает и что остаётся — карточкой со значком: значок говорит о судьбе сведений раньше,
 * чем человек дочитает заголовок. Пункты идут **строками**, а не одним абзацем: список читают
 * глазами по одному, а абзац перечитывают целиком. Одна на все последствия — общей полки (№20)
 * и утраченного ключа (№1): человек читает их одинаково.
 */
@Composable
fun ConsequenceCard(@DrawableRes icon: Int, @StringRes title: Int, @ArrayRes items: Int, tint: Color) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = tint)
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = tint)
            }
            for (item in stringArrayResource(items)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = tint)
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
