package com.kert0n.medapp.ui.medkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.MedKitJoiningRefusal
import com.kert0n.medapp.presentation.medkit.MedKitJoiningUiState
import com.kert0n.medapp.ui.text

/**
 * Присоединиться к чужой аптечке (PLAN H3 №22). Первым — поле кода: за этим сюда и приходят.
 * Отсканировать код камерой можно будет с U9; до тех пор код вводится руками, и это деградация,
 * а не дыра.
 *
 * **Кнопка не гаснет** — как и у всех форм приложения: погашенная не объясняет, чего не хватает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitJoiningScreen(
    state: MedKitJoiningUiState,
    onType: (String) -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.med_kit_joining_menu)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.med_kit_joining_explained),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.code,
                onValueChange = onType,
                label = { Text(stringResource(R.string.med_kit_joining_code)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
                singleLine = true,
                isError = state.refusal != null,
                modifier = Modifier.fillMaxWidth()
            )
            state.refusal?.let {
                Text(
                    stringResource(it.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onJoin,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(painterResource(R.drawable.ic_group_add), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.med_kit_joining_join))
            }
        }
    }
}

/** Слова отказа — его свойство: экран не выбирает, какими словами называть неудачу (PLAN H3). */
private val MedKitJoiningRefusal.text: Int
    get() = when (this) {
        MedKitJoiningRefusal.Empty -> R.string.med_kit_joining_code_empty
        MedKitJoiningRefusal.Invalid -> R.string.med_kit_joining_invalid
        MedKitJoiningRefusal.AlreadyMember -> R.string.med_kit_joining_already_member
        is MedKitJoiningRefusal.Unavailable -> reason.text
    }
