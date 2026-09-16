package com.kert0n.medapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Форма: поля прокручиваются в своём окне, а действия стоят **подвалом** и видны всегда (PLAN H3
 * «Дизайн», C1 «Действия формы прижаты к низу»). Сохранить хочется оттуда, где человек дописал, а
 * не с конца списка: у длинной формы — назначение лечения, сведения о коробке — кнопка уезжала за
 * край тем дальше, чем больше заполнено, и на маленьком экране с крупным шрифтом до неё
 * приходилось долистывать.
 *
 * **Пока человек набирает, подвала нет** ([typing]): клавиатура занимает половину экрана, и три
 * кнопки над ней съедают остаток формы — у нижнего поля прячут и его соседей (находка владельца
 * 2026-09-16). Поля при этом поднимаются над клавиатурой ([imePadding]), чтобы набираемое было
 * видно, а действия возвращаются, как только её убрали: за «Сохранить» человек идёт туда же.
 *
 * Подвал отделён чертой. В [actions] кладут и отказ записи — он читается в тот момент, когда
 * нажали. Отступы и промежутки — здесь: четыре одинаковых хвоста расходились бы поодиночке.
 */
@Composable
fun Form(
    modifier: Modifier = Modifier,
    typing: Boolean = keyboardIsUp(),
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
        if (typing) return@Column
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

/**
 * Набирает ли человек прямо сейчас — по клавиатуре на экране. Спрашивается у системы, а не у формы:
 * поле не знает, открыта ли она, а проверке настоящую клавиатуру не показать, поэтому признак у
 * [Form] называется словом и приходит параметром.
 */
@Composable
private fun keyboardIsUp(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0
