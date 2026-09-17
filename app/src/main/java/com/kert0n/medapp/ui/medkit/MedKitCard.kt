package com.kert0n.medapp.ui.medkit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.ui.theme.LocalAccents

/**
 * Аптечка строкой списка (PLAN H3 №2). Первым — не название, а **что внутри**: список нужен
 * человеку, чтобы решить, куда идти.
 *
 * Нажимается вся карточка, а не одна её строка: цель ниже 48 dp не бывает, и гадать, куда
 * ткнуть, человеку незачем.
 */
@Composable
fun MedKitCard(medKit: MedKitPresentationDTO, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(medKit.name, style = MaterialTheme.typography.titleMedium)

            val packages = medKit.contents.packages
            Text(
                text = if (packages == 0) {
                    stringResource(R.string.med_kit_empty)
                } else {
                    pluralStringResource(R.plurals.packages_count, packages, packages)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Просрочка — значком и словами: цвет сам по себе сообщением не бывает (PLAN H3).
            if (medKit.contents.expired > 0) {
                Marker(
                    icon = R.drawable.ic_expired,
                    text = pluralStringResource(
                        R.plurals.packages_expired, medKit.contents.expired, medKit.contents.expired
                    ),
                    color = MaterialTheme.colorScheme.error
                )
            }

            medKit.location?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Решение о полке, которое ещё едет серверу, видно в списке: иначе человек жмёт
            // «пригласить» и не понимает, почему нельзя (PLAN E5).
            when (medKit.status) {
                MedKitStatus.PUBLISHING -> Marker(
                    icon = R.drawable.ic_cloud_upload,
                    text = stringResource(R.string.med_kit_publishing),
                    color = LocalAccents.current.pending
                )
                MedKitStatus.REMOVING -> Marker(
                    icon = R.drawable.ic_delete,
                    text = stringResource(R.string.med_kit_removing),
                    color = LocalAccents.current.pending
                )
                MedKitStatus.ACTIVE -> Unit
            }

            if (medKit.isShared) {
                Marker(
                    icon = R.drawable.ic_shared,
                    text = stringResource(
                        R.string.med_kit_shared,
                        pluralStringResource(
                            R.plurals.participants_count,
                            medKit.participantCount.toInt(),
                            medKit.participantCount.toInt()
                        )
                    ),
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
