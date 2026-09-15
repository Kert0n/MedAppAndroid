package com.kert0n.medapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Как дата выглядит в поле: так же, как её печатают от руки. */
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")

/**
 * Выбор из готового списка — аптечка, единица, форма. Поле только для чтения: выбранное
 * приходит из словаря или из базы, и напечатать сюда несуществующее нельзя по устройству, а не
 * по проверке.
 *
 * Пустой список — не молчание: сказать «выбирать не из чего» важнее, чем показать пустое меню.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerField(
    label: String,
    selected: T?,
    options: List<T>,
    optionText: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    emptyText: String? = null
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.let(optionText).orEmpty(),
            onValueChange = {},
            readOnly = true,
            isError = isError,
            label = { Text(label) },
            supportingText = if (options.isEmpty() && emptyText != null) {
                { Text(emptyText) }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onPick(option)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Дата, которую называет календарь. Печатать её строкой незачем: выбранная дата уже дата, и
 * состояния «13.20» у неё не бывает — потому у дат покупки и вскрытия нет своего разбора ввода,
 * в отличие от срока годности, который перепечатывают с упаковки как есть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value?.format(DAY).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    IconButton(onClick = { onPick(null) }) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_clear_date),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = { open = true }) {
                    Icon(
                        painterResource(R.drawable.ic_calendar_month),
                        contentDescription = stringResource(R.string.action_pick_date)
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
    )
    if (!open) return
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(picker.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    })
                    open = false
                }
            ) { Text(stringResource(R.string.action_choose)) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
        }
    ) { DatePicker(picker) }
}

/**
 * Строка поиска, которая сама ничего не ищет: она ведёт туда, где ищут. Нужна там, где результат
 * — другой экран (PLAN H3: поиск на списке аптечек показывает все лекарства), и притворяться
 * полем ввода ей нельзя — человек напечатал бы в неё и не понял, почему ничего не происходит.
 */
@Composable
fun SearchEntry(hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null)
            Text(
                hint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Раздел, который человек раскрывает сам. Нужен там, где полей больше, чем он заполняет за раз:
 * все они есть, но сразу видны обязательные (PLAN H3 №7).
 */
@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                painterResource(
                    if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
                ),
                contentDescription = null
            )
        }
        if (expanded) content()
    }
}
