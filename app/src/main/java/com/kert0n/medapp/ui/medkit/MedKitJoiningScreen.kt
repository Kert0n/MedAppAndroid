package com.kert0n.medapp.ui.medkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.presentation.medkit.MedKitJoiningRefusal
import com.kert0n.medapp.presentation.medkit.MedKitJoiningUiState
import com.kert0n.medapp.ui.NavigationRow
import com.kert0n.medapp.ui.scan.CodeViewfinder
import com.kert0n.medapp.ui.text

/**
 * Присоединиться к чужой аптечке (PLAN H3 №22). Первым — поле кода: за этим сюда и приходят.
 * Рядом — «Отсканировать код»: приглашение чаще показывают с экрана телефона, чем переписывают,
 * и камера кладёт ключ в то же поле (U9). Камера открывается **поверх этого экрана**, а не своим
 * маршрутом: ключ секрет и в маршрут не едет (PLAN G3).
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
    modifier: Modifier = Modifier,
    onScan: () -> Unit = {},
    onStopScanning: () -> Unit = {},
    onCode: (ScannedCode) -> Unit = {}
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
            // Код либо переписывают в поле, либо наводят на него камеру — и то и другое до
            // вступления. Переход к камере — строка со значком: она уводит, а не записывает
            // (PLAN H3 «Дизайн»), и двух кнопок в ряд не бывает вовсе.
            NavigationRow(
                icon = R.drawable.ic_photo_camera,
                text = stringResource(R.string.med_kit_joining_scan),
                onClick = onScan
            )
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
    if (state.isScanning) {
        Camera(onCode = onCode, onClose = onStopScanning)
    }
}

/**
 * Камера поверх экрана: узнанный QR кладёт ключ в поле и зовёт вступление — за этим человек её и
 * открывал. Окно, а не маршрут: ключ не должен попасть в сохранённую стопку (PLAN G3).
 */
@Composable
private fun Camera(onCode: (ScannedCode) -> Unit, onClose: () -> Unit) = Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false)
) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            CodeViewfinder(onCode, hint = stringResource(R.string.med_kit_joining_aim))
            // Крестик лежит на подложке: поверх живой картинки любой его цвет то виден, то нет.
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_close)
                    )
                }
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
        MedKitJoiningRefusal.CameraDenied -> R.string.med_kit_joining_camera_denied
        is MedKitJoiningRefusal.Unavailable -> reason.text
    }
