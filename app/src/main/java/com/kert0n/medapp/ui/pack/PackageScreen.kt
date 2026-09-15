package com.kert0n.medapp.ui.pack

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.PackageRefusal
import com.kert0n.medapp.presentation.pack.PackageUiState
import com.kert0n.medapp.presentation.pack.PackageViewModel
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.theme.accents
import java.time.format.DateTimeFormatter
import java.util.Currency
import kotlin.uuid.Uuid

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")

/** Где карточка берёт состояние и куда уходит, когда коробки не стало. */
@Composable
fun PackageRoute(
    onBack: () -> Unit,
    onEdit: (Uuid) -> Unit,
    onChangeAmount: (Uuid) -> Unit,
    onTransfer: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PackageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isRemoved) { if (state.isRemoved) onBack() }
    PackageScreen(
        state = state,
        onBack = onBack,
        onEdit = { state.pack?.id?.let(onEdit) },
        onChangeAmount = { state.pack?.id?.let(onChangeAmount) },
        onTransfer = { state.pack?.id?.let(onTransfer) },
        onAskToRemove = viewModel::askToRemove,
        onConfirmRemoval = viewModel::remove,
        onDismissRemoval = viewModel::dismissRemoval,
        modifier = modifier
    )
}

/**
 * Карточка упаковки (PLAN H3 №6). Сверху — то, ради чего её открывают: сколько есть. Ниже
 * карточками по смыслу: что это, сроки и цена, где лежит, что можно сделать. Незаполненного не
 * показывается вовсе — пустая строка «производитель: —» занимает место и ничего не сообщает.
 *
 * «Доступно мне» и «свободно любому» показываются, только когда отличаются от предыдущего числа:
 * три одинаковых числа подряд человек читает как ошибку (PLAN D4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageScreen(
    state: PackageUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeAmount: () -> Unit,
    onTransfer: () -> Unit,
    onAskToRemove: () -> Unit,
    onConfirmRemoval: () -> Unit,
    onDismissRemoval: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pack = state.pack
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(pack?.name ?: stringResource(R.string.pack_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (pack != null) {
                        IconButton(onClick = onEdit) {
                            Icon(
                                painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.pack_action_edit)
                            )
                        }
                        IconButton(onClick = onAskToRemove) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.pack_action_remove)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isGone -> EmptyState(
                text = stringResource(R.string.pack_gone),
                modifier = Modifier.padding(padding)
            )
            pack == null -> LoadingState(Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HowMuchIsThere(pack, state.holdingCourseTitle, onChangeAmount)
                WhatItIs(pack)
                DatesAndPrice(pack, state)
                WhereItLies(pack, state.medKitName, onTransfer)
                state.refusal?.let {
                    Text(
                        stringResource(it.text),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
    if (state.asksToRemove) RemovalDialog(onConfirmRemoval, onDismissRemoval)
}

/**
 * Сколько есть. Крупно — оценка остатка: то, из чего человек исходит, собираясь принять. Чужие
 * брони янтарным: они не беда и не просрочка, просто это лекарство заявлено не мной (PLAN D4).
 */
@Composable
private fun HowMuchIsThere(
    pack: PackagePresentationDTO,
    holdingCourseTitle: String?,
    onChangeAmount: () -> Unit
) {
    Section(
        title = stringResource(R.string.pack_how_much),
        action = R.drawable.ic_calculate,
        actionDescription = stringResource(R.string.pack_action_recount),
        onAction = onChangeAmount
    ) {
        Text(pack.effective.text(), style = MaterialTheme.typography.headlineMedium)
        if (pack.availableToMe != pack.effective) {
            Fact(stringResource(R.string.pack_available_to_me), pack.availableToMe.text())
        }
        if (pack.freeForAnyone != pack.availableToMe) {
            Fact(stringResource(R.string.pack_free_for_anyone), pack.freeForAnyone.text())
        }
        if (pack.reservedByOthers.amount != "0") {
            Marker(
                icon = R.drawable.ic_warning,
                text = stringResource(R.string.pack_reserved_by_others, pack.reservedByOthers.text()),
                description = stringResource(R.string.pack_reserved_description),
                color = MaterialTheme.accents.reserved
            )
        }
        holdingCourseTitle?.let {
            Marker(
                icon = R.drawable.ic_medication,
                text = stringResource(R.string.pack_held_by_course, it),
                description = stringResource(R.string.pack_held_description),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (pack.hasUnconfirmedChanges) {
            Text(
                stringResource(R.string.pack_unconfirmed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Что это за лекарство. Карточка не показывается вовсе, когда сказать о нём нечего. */
@Composable
private fun WhatItIs(pack: PackagePresentationDTO) {
    val known = listOfNotNull(
        pack.form?.let { stringResource(R.string.pack_form) to it.name },
        pack.category?.let { stringResource(R.string.pack_category) to it },
        pack.manufacturer?.let { stringResource(R.string.pack_manufacturer) to it },
        pack.country?.let { stringResource(R.string.pack_country) to it },
        pack.description?.let { stringResource(R.string.pack_description) to it }
    )
    if (known.isEmpty()) return
    Section(stringResource(R.string.pack_what_it_is)) {
        for ((label, value) in known) Fact(label, value)
    }
}

/** Сроки и цена. Просрочка — красным, значком и словом: цветом одним нельзя (PLAN H3). */
@Composable
private fun DatesAndPrice(pack: PackagePresentationDTO, state: PackageUiState) {
    Section(stringResource(R.string.pack_dates)) {
        when {
            pack.expiresOn == null ->
                Fact(stringResource(R.string.pack_expires_on), stringResource(R.string.pack_expiry_unknown))
            pack.expiresOn.isExpiredOn(state.today) -> Marker(
                icon = R.drawable.ic_expired,
                text = stringResource(R.string.pack_expired_on, pack.expiresOn.toPresentationDTO().text),
                description = stringResource(R.string.med_kit_expired_description),
                color = MaterialTheme.colorScheme.error
            )
            pack.expiresOn.expiresWithin(state.today, ExpiryDate.SOON_DAYS) -> Marker(
                icon = R.drawable.ic_warning,
                text = stringResource(R.string.pack_expires_soon, pack.expiresOn.toPresentationDTO().text),
                description = stringResource(R.string.pack_expires_soon_description),
                color = MaterialTheme.accents.reserved
            )
            else -> Fact(
                stringResource(R.string.pack_expires_on),
                stringResource(R.string.pack_expires_until, pack.expiresOn.toPresentationDTO().text)
            )
        }
        pack.defaultIntakeAmount?.let { Fact(stringResource(R.string.pack_intake_hint), it.text()) }
        pack.lastUsedAt?.let {
            Fact(
                stringResource(R.string.pack_last_used),
                DAY.format(it.atZone(java.time.ZoneId.systemDefault()))
            )
        }
        pack.purchasedOn?.let { Fact(stringResource(R.string.pack_purchased_on), it.format(DAY)) }
        pack.openedOn?.let { Fact(stringResource(R.string.pack_opened_on), it.format(DAY)) }
        pack.price?.let { Fact(stringResource(R.string.pack_price_label), it.human()) }
    }
}

/** Где лежит: аптечка и заметка человека — то, что помогает найти коробку руками. */
@Composable
private fun WhereItLies(pack: PackagePresentationDTO, medKitName: String?, onTransfer: () -> Unit) {
    Section(
        title = stringResource(R.string.pack_where),
        action = R.drawable.ic_move_down,
        actionDescription = stringResource(R.string.pack_action_transfer),
        onAction = onTransfer
    ) {
        Fact(
            stringResource(R.string.pack_med_kit),
            medKitName ?: stringResource(R.string.pack_med_kit_unknown)
        )
        pack.note?.let { Fact(stringResource(R.string.pack_note), it) }
    }
}

/** Удаление необратимо, поэтому спрашивается — и сказано, что именно исчезнет (PLAN D3, D6). */
@Composable
private fun RemovalDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pack_remove_title)) },
        text = { Text(stringResource(R.string.pack_remove_explained)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Часть карточки. Действие живёт **в заголовке своей части**, а не списком в конце: пересчёт
 * стоит там, где число, перенос — там, где место. Так человек нажимает на то, на что смотрит, а
 * не ищет по экрану, к чему относится кнопка. Правка и удаление трогают коробку целиком, и живут
 * они у окна — в верхней панели.
 */
@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes action: Int? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (action != null && onAction != null) {
                    IconButton(onClick = onAction) {
                        Icon(painterResource(action), contentDescription = actionDescription)
                    }
                }
            }
            content()
        }
    }
}

/** Сведение: чему оно относится и что это. Подпись сверху — так её читает и экранный чтец. */
@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Значок со словами: цвет уточняет то, что уже сказано, а не заменяет сказанное (PLAN H3). */
@Composable
private fun Marker(@DrawableRes icon: Int, text: String, description: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Цена словами: сумма и знак валюты — на нём человек и узнаёт её. */
@Composable
private fun MoneyPresentationDTO.human(): String =
    stringResource(R.string.pack_price_value, amount, Currency.getInstance(currencyCode).symbol)

/** Количество словами: число и единица, как их показывают везде в приложении. */
@Composable
private fun QuantityPresentationDTO.text(): String =
    stringResource(R.string.pack_left, amount, unit.name)

/** Текст причины — её свойство: экран не подбирает слова сам. */
@get:StringRes
private val PackageRefusal.text: Int
    get() = when (this) {
        PackageRefusal.REMOVAL_ON_THE_WAY -> R.string.pack_removal_on_the_way
        PackageRefusal.BUSY -> R.string.pack_busy
    }
