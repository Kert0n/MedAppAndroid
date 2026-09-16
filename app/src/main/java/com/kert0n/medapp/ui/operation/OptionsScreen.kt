package com.kert0n.medapp.ui.operation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.NavigationRow

/**
 * Место «Опции» (PLAN H3): комната, из которой уходят вглубь, и её постоянная форма — список
 * строк. Сейчас в ней одна — состояние синхронизации; настройки, разрешения и учётная запись
 * (№27) дописываются строками в U11, ничего не переделывая.
 *
 * Строка говорит, есть ли о чём беспокоиться: очередь, которая чего-то ждёт, названа прямо здесь,
 * иначе человек узнавал бы о ней только из уведомления.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    outstanding: Int,
    onSyncStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.options_title)) }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                NavigationRow(
                    icon = if (outstanding > 0) R.drawable.ic_sync_problem else R.drawable.ic_sync,
                    text = stringResource(R.string.sync_status),
                    supporting = stringResource(
                        if (outstanding > 0) R.string.sync_status_attention else R.string.sync_status_supporting
                    ),
                    onClick = onSyncStatus
                )
            }
        }
    }
}
