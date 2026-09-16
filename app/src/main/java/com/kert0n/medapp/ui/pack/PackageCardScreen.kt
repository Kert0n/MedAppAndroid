package com.kert0n.medapp.ui.pack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.presentation.pack.PackageCardUiState
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import java.time.LocalDate
import java.util.Currency

/**
 * Карточка упаковки (PLAN H3 №6). Сверху — то, ради чего её открывают: сколько есть. Ниже
 * карточками по смыслу: что это, сроки и цена, где лежит. Незаполненного не показывается вовсе
 * — «производитель: —» занимает место и ничего не сообщает; «срок не указан», наоборот,
 * показывается: это ответ, а не пустота.
 *
 * **Действия живут по местам**: пересчёт — там, где число, перенос — там, где место; правка и
 * «выбросить» трогают коробку целиком и стоят в верхней панели. Подтверждение — только у
 * «выбросить»: остальное обратимо, а выброшенной пачки не вернуть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageCardScreen(
    state: PackageCardUiState,
    onEdit: () -> Unit,
    onTake: () -> Unit,
    onRecount: () -> Unit,
    onTransfer: () -> Unit,
    onAskToRemove: () -> Unit,
    onConfirmRemoval: () -> Unit,
    onDismissRemoval: () -> Unit,
    onBack: () -> Unit,
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
            state.isGone -> EmptyState(text = stringResource(R.string.pack_gone), modifier = Modifier.padding(padding))
            pack == null -> LoadingState(Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HowMuchIsThere(pack, state.holdingCourseTitle, state.isBusy, onTake, onRecount)
                WhatItIs(pack)
                DatesAndPrice(pack, state.today, state.lastUsedOn)
                WhereItLies(pack, state.medKitName, onTransfer)
            }
        }
    }
    if (state.asksToRemove) RemovalDialog(onConfirmRemoval, onDismissRemoval)
}

/**
 * Сколько есть. Крупно — оценка: из неё человек исходит, собираясь принять. «Доступно мне» и
 * «свободно любому» — каждое, только если отличается от предыдущего: три одинаковых числа подряд
 * читаются как ошибка (PLAN D4). Чужие брони — `tertiary`, значком и словами: это не беда и не
 * просрочка, просто лекарство заявлено не мной.
 */
@Composable
private fun HowMuchIsThere(
    pack: PackagePresentationDTO,
    holdingCourseTitle: String?,
    isBusy: Boolean,
    onTake: () -> Unit,
    onRecount: () -> Unit
) {
    Section(title = stringResource(R.string.pack_how_much)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pack.effective.text(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            // Приём и пересчёт — оба про число, и стоят у числа: одно уменьшает его по факту,
            // другое исправляет по виду (H3 №6, «действия живут по местам»).
            TextButton(onClick = onTake) { Text(stringResource(R.string.intake_record)) }
            TextButton(onClick = onRecount) { Text(stringResource(R.string.pack_recount)) }
        }
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
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        holdingCourseTitle?.let {
            Marker(
                icon = R.drawable.ic_medication,
                text = stringResource(R.string.pack_held_by_course, it),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        // О пометках говорит сама коробка: они переживают закрытие карточки, а отказ — нет.
        if (pack.hasUnconfirmedChanges) Note(stringResource(R.string.pack_unconfirmed))
        when (pack.status) {
            PackageStatus.REMOVING -> Note(stringResource(R.string.pack_removal_on_the_way))
            PackageStatus.LOST -> Note(stringResource(R.string.pack_lost))
            PackageStatus.ACTIVE, PackageStatus.CHANGING -> Unit
        }
        if (isBusy) {
            Text(
                stringResource(R.string.pack_remove_busy),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Что это за лекарство. Карточки нет вовсе, когда сказать о нём нечего. */
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

/** Сроки и цена. Просрочка — `error`, значком и словами: цветом одним нельзя (PLAN H3). */
@Composable
private fun DatesAndPrice(pack: PackagePresentationDTO, today: LocalDate, lastUsedOn: LocalDate?) {
    Section(stringResource(R.string.pack_dates)) {
        val expiry = pack.expiresOn
        when {
            expiry == null -> Fact(stringResource(R.string.pack_expiry), stringResource(R.string.pack_expiry_unknown))
            expiry.isExpiredOn(today) -> Marker(
                icon = R.drawable.ic_expired,
                text = stringResource(R.string.pack_expired_on, expiry.toPresentationDTO().text),
                color = MaterialTheme.colorScheme.error
            )
            expiry.expiresWithin(today, ExpiryDate.SOON_DAYS) -> Marker(
                icon = R.drawable.ic_expiring_soon,
                text = stringResource(R.string.pack_expires_on, expiry.toPresentationDTO().text),
                color = MaterialTheme.colorScheme.tertiary
            )
            else -> Fact(
                stringResource(R.string.pack_expiry),
                stringResource(R.string.pack_expires_until, expiry.toPresentationDTO().text)
            )
        }
        pack.defaultIntakeAmount?.let { Fact(stringResource(R.string.pack_hint), it.text()) }
        lastUsedOn?.let { Fact(stringResource(R.string.pack_last_used), it.format(DAY)) }
        pack.purchasedOn?.let { Fact(stringResource(R.string.pack_purchased_on), it.format(DAY)) }
        pack.openedOn?.let { Fact(stringResource(R.string.pack_opened_on), it.format(DAY)) }
        pack.price?.let { Fact(stringResource(R.string.pack_price), it.text()) }
    }
}

/** Где лежит: аптечка по имени и заметка человека — то, что помогает найти коробку руками. */
@Composable
private fun WhereItLies(pack: PackagePresentationDTO, medKitName: String?, onTransfer: () -> Unit) {
    Section(stringResource(R.string.pack_where)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Fact(
                stringResource(R.string.pack_med_kit),
                medKitName ?: stringResource(R.string.pack_med_kit_unknown),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onTransfer) { Text(stringResource(R.string.pack_action_transfer)) }
        }
        pack.note?.let { Fact(stringResource(R.string.pack_note), it) }
    }
}

/** Выброшенной пачки не вернуть, поэтому спрашивается — и сказано, что останется (PLAN D6). */
@Composable
private fun RemovalDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pack_remove_title)) },
        text = { Text(stringResource(R.string.pack_remove_explained)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.pack_action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Часть карточки: заголовок и то, что в ней известно. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Сведение: к чему оно относится и что это. Подпись сверху — так её читает и экранный чтец. */
@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Пометка коробки: не отказ и не беда, просто состояние, о котором стоит знать. */
@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Количество словами: число и единица, как их показывают везде в приложении. */
@Composable
private fun QuantityPresentationDTO.text(): String = stringResource(R.string.pack_left, amount, unit.name)

/** Цена словами: сумма и знак валюты — по нему человек её и узнаёт; незнакомый код — сам код. */
@Composable
private fun MoneyPresentationDTO.text(): String =
    stringResource(R.string.pack_price_value, amount, attempt { Currency.getInstance(currencyCode).symbol }.getOrDefault(currencyCode))
