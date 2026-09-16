package com.kert0n.medapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationError
import com.kert0n.medapp.presentation.value.MoneyPresentationError
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
 * Выбор из готового — аптечка, единица, форма. Поле только для чтения: выбранное приходит из
 * словаря или из базы, и напечатать сюда несуществующее нельзя **по устройству**, а не по
 * проверке.
 *
 * Пустой список — не молчание: сказать «выбирать не из чего» важнее, чем показать пустое меню.
 * [supporting] — подпись для всего остального, например «обязательно» у незаполненного поля.
 *
 * **Содержимое меню ленивое.** Меню Material меряет его `IntrinsicSize.Max`, и ленивый список
 * внутрь так не встаёт — поэтому он лежит в рамке заданного размера: рамка отвечает на запрос
 * внутренних размеров сама, и мерить каждый пункт не нужно. Размер именно **задан**, а не
 * ограничен сверху: `heightIn(max)` переспрашивает список, и меню падает при первом открытии.
 * Высота считается по числу пунктов, чтобы короткий словарь не тянул за собой пустую рамку.
 * Длина списка приходит из словаря сервера, а не из экрана, и снова вырасти она может в любой
 * день (issue #36).
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
    emptyText: String? = null,
    supporting: String? = null
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
            // «Выбирать не из чего» важнее, чем «обязательно»: второе человек и так исправить
            // не может, пока нечего выбрать.
            supportingText = (emptyText?.takeIf { options.isEmpty() } ?: supporting)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LazyColumn(Modifier.width(MENU_WIDTH).height((MENU_ITEM_HEIGHT * options.size).coerceAtMost(MENU_MAX_HEIGHT))) {
                items(options) { option ->
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
}

/** Ширина рамки списка: она же ответ на запрос внутренних размеров, который делает меню. */
private val MENU_WIDTH = 280.dp

/** Насколько отрастает меню, прежде чем начать прокручиваться. */
private val MENU_MAX_HEIGHT = 320.dp

/** Высота пункта меню Material: по ней рамка считает свою высоту, не меряя пункты. */
private val MENU_ITEM_HEIGHT = 48.dp

/**
 * Дата, которую называет календарь. Печатать её строкой незачем: выбранная дата уже дата, и
 * состояния «13.20» у неё не бывает — потому у дат покупки и вскрытия нет своего разбора ввода,
 * в отличие от срока годности, который перепечатывают с упаковки как есть.
 *
 * Календарь открывает **всё поле**, а не один значок: «зоны нажатия от 48 dp» (PLAN H3 «Дизайн»)
 * — про зону, а не про высоту рамки, и в поле во всю ширину живого было 24 dp. Открытие ловится
 * нажатием на само поле ([PressInteraction.Release]), потому что поле остаётся `readOnly` —
 * печатать дату по-прежнему нечем, и клавиатура не всплывает. Значок при этом остаётся: он
 * называет действие экранному чтецу и виден глазу; крестик очистки — своя кнопка, и своё нажатие
 * она тратит на себя.
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
    val presses = remember { MutableInteractionSource() }
    LaunchedEffect(presses) {
        presses.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
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
        interactionSource = presses,
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
                    onPick(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
                    open = false
                }
            ) { Text(stringResource(R.string.action_choose)) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
        }
    ) { DatePicker(picker) }
}

/** Как дата выглядит в поле: так же, как её печатают от руки. */

/**
 * Чем плох напечатанный ввод — словами. Один текст на все поля, где человек печатает количество,
 * срок или цену: заведение коробки, пересчёт; правило разбора живёт у представления, слова — здесь.
 */
@get:StringRes
internal val QuantityPresentationError.text: Int
    get() = when (this) {
        QuantityPresentationError.EMPTY -> R.string.quantity_empty
        QuantityPresentationError.TOO_LONG -> R.string.quantity_too_long
        QuantityPresentationError.NOT_A_DECIMAL -> R.string.quantity_not_a_number
        QuantityPresentationError.TOO_MANY_FRACTION_DIGITS -> R.string.quantity_too_precise
        QuantityPresentationError.TOO_MANY_INTEGER_DIGITS -> R.string.quantity_too_big
        QuantityPresentationError.OUT_OF_DOMAIN_RANGE -> R.string.quantity_too_big
        QuantityPresentationError.UNKNOWN_UNIT -> R.string.pack_unknown_in_vocabulary
    }

@get:StringRes
internal val ExpiryDatePresentationError.text: Int
    get() = when (this) {
        ExpiryDatePresentationError.EMPTY -> R.string.expiry_empty
        ExpiryDatePresentationError.UNKNOWN_FORMAT -> R.string.expiry_unknown_shape
        ExpiryDatePresentationError.IMPOSSIBLE_DATE -> R.string.expiry_impossible
    }

@get:StringRes
internal val MoneyPresentationError.text: Int
    get() = when (this) {
        MoneyPresentationError.EMPTY -> R.string.price_empty
        MoneyPresentationError.TOO_LONG -> R.string.price_too_long
        MoneyPresentationError.NOT_A_DECIMAL -> R.string.price_not_a_number
        MoneyPresentationError.UNKNOWN_CURRENCY -> R.string.price_unknown_currency
        MoneyPresentationError.OUT_OF_CURRENCY_RANGE -> R.string.price_too_big
    }

/**
 * Поиск по списку: строка с крестиком, который её очищает. Один и тот же на полке и в выборе
 * источника — искать человек привык одинаково, и двум копиям этого поля незачем расходиться.
 *
 * Отбор поле не делает: оно отдаёт набранное тому, кто спрашивает хранение (PLAN H4).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear_search)
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
