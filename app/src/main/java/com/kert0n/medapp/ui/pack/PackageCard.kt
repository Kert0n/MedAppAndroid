package com.kert0n.medapp.ui.pack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.LocalDate

/**
 * Коробка строкой списка (PLAN H3 №4, №5). Название, сколько в ней есть и — если о сроке есть
 * что сказать — срок словами.
 *
 * **Просроченная залита целиком** (`errorContainer`), а не помечена уголком: список ведёт
 * человека к тому, что надо выбросить, и такую строку он обязан увидеть, не вчитываясь. Но
 * заливка не сообщение: рядом стоят значок и слова «Просрочен 03.2025» — по одной заливке
 * человек, не различающий цвета, не узнает ничего.
 *
 * [placeName] называет аптечку и приходит только с экрана всех лекарств: внутри одной полки
 * повторять её на каждой строке незачем — человек знает, куда пришёл.
 */
@Composable
fun PackageCard(
    pkg: PackagePresentationDTO,
    today: LocalDate,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    placeName: String? = null
) {
    val expired = pkg.expiresOn?.isExpiredOn(today) == true
    ElevatedCard(
        onClick = onOpen,
        colors = if (expired) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        } else {
            CardDefaults.elevatedCardColors()
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pkg.name, style = MaterialTheme.typography.titleMedium)

            Text(
                stringResource(R.string.pack_left, pkg.quantity.amount, pkg.quantity.unit.name),
                style = MaterialTheme.typography.bodyMedium
            )

            placeName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            val expiry = pkg.expiresOn
            when {
                expiry == null -> Unit
                expired -> Marker(
                    icon = R.drawable.ic_expired,
                    text = stringResource(R.string.pack_expired_on, expiry.toPresentationDTO().text),
                    // Внутри залитой карточки цвет содержимого уже её: свой сделал бы надпись
                    // нечитаемой на этой подложке.
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                expiry.expiresWithin(today, ExpiryDate.SOON_DAYS) -> Marker(
                    icon = R.drawable.ic_expiring_soon,
                    text = stringResource(R.string.pack_expires_on, expiry.toPresentationDTO().text),
                    // Третий тон схемы, а не янтарь из головы: в тёмной теме своя краска
                    // перестаёт читаться, а сообщение здесь несут значок и слова.
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/** Значок со словами: цвет уточняет сказанное, а не заменяет его. */
@Composable
private fun Marker(@DrawableRes icon: Int, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Значок молчит для экранного чтеца: рядом стоят те же слова, и повторять их незачем.
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
