package com.kert0n.medapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Форма: поля прокручиваются в своём окне, а действия стоят **подвалом** и видны всегда (PLAN H3
 * «Дизайн», C1 «Действия формы прижаты к низу»). Сохранить хочется оттуда, где человек дописал, а
 * не с конца списка: у длинной формы — назначение лечения, сведения о коробке — кнопка уезжала за
 * край тем дальше, чем больше заполнено, и на маленьком экране с крупным шрифтом до неё
 * приходилось долистывать.
 *
 * Подвал отделён чертой и поднимается над клавиатурой ([imePadding]): набирая число, человек видит,
 * куда нажать дальше. В [actions] кладут и отказ записи — он читается в тот момент, когда нажали.
 *
 * Отступы и промежутки — здесь: четыре одинаковых хвоста расходились бы поодиночке.
 */
@Composable
fun Form(
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
    fields: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = fields
        )
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions
            )
        }
    }
}
