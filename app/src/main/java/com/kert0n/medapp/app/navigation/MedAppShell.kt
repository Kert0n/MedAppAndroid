package com.kert0n.medapp.app.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.NavDisplay
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.MedKitFormUiState
import com.kert0n.medapp.presentation.medkit.MedKitFormViewModel
import com.kert0n.medapp.presentation.medkit.MedKitListViewModel
import com.kert0n.medapp.presentation.pack.PackageFormViewModel
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.medkit.MedKitFormScreen
import com.kert0n.medapp.ui.medkit.MedKitListScreen
import com.kert0n.medapp.ui.pack.PackageFormScreen

/**
 * Оболочка приложения: пять мест внизу и содержимое над ними. Где человек стоит и как глубоко —
 * живёт в [TabStacks] и переживает поворот и смерть процесса.
 *
 * **Своего движения у оболочки нет.** Переходы играет библиотека, и возврат выглядит одинаково,
 * пальцем его сделали или кнопкой. Собственная система движения поверх библиотеки, которая
 * ведёт предиктивный жест по-своему, — это борьба с библиотекой, и прошлый заход на ней и
 * кончился (отклонённый PR #33).
 *
 * **Отступы системы оболочка не только отдаёт, но и поглощает.** `Modifier.padding(padding)`
 * оставляет место под строкой состояния и полосой жестов — и только; сами вставки остаются
 * видны тому, кто внутри, и свой `Scaffold` каждого экрана берёт их второй раз. Беда одна на
 * все экраны, поэтому и лечится она здесь.
 */
@Composable
fun MedAppShell(modifier: Modifier = Modifier, stacks: TabStacks = rememberTabStacks()) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = { Places(stacks) }
    ) { padding ->
        NavDisplay(
            entries = stacks.entries(remember(stacks) { screens(stacks) }),
            onBack = stacks::back,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        )
    }
}

/**
 * Что показывает каждый ключ. Пока своего экрана у места нет, за ним стоит общее «пусто» — то
 * самое, которое потом покажет настоящий экран, когда показывать действительно нечего.
 *
 * Состояние экрану даёт `hiltViewModel` здесь же, а аргумент приходит **значением из ключа**:
 * экран получает `state` и действия и больше ничего (PLAN H1).
 */
private fun screens(stacks: TabStacks) = entryProvider<NavKey> {
    entry(Screen.MedKits) {
        val model: MedKitListViewModel = hiltViewModel()
        MedKitListScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            // Вглубь отсюда пока некуда: содержимое полки и все лекарства — следующие коммиты.
            onOpen = {},
            onAdd = { stacks.go(Screen.MedKitForm()) },
            onSearch = {}
        )
    }
    entry<Screen.MedKitForm> { key ->
        val model = hiltViewModel<MedKitFormViewModel, MedKitFormViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.medKitId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Записанное — повод уйти: человек заводил полку, а не форму, и возвращаться ему в неё
        // незачем.
        LaunchedEffect(state) { if (state is MedKitFormUiState.Editing && state.isSaved) stacks.back() }
        MedKitFormScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onCancel = stacks::back
        )
    }
    entry<Screen.PackageForm> { key ->
        val model = hiltViewModel<PackageFormViewModel, PackageFormViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory ->
                factory.create(PackageFormViewModel.Opened(key.medKitId, key.packageId))
            }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Записанная коробка открывается карточкой — её пока нет, поэтому просто уходим: форма
        // своё дело сделала.
        LaunchedEffect(state.saved) { if (state.saved != null) stacks.back() }
        PackageFormScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onCancel = stacks::back,
            // Пересчёт — следующий экран набора; пока вести некуда.
            onRecount = {}
        )
    }
    for (place in Place.entries - Place.MED_KITS) {
        entry(place.key) { NotReadyYet() }
    }
}

/**
 * Переключение мест. Повторное нажатие на своё место возвращает его к началу, а переход на
 * чужое сохраняет, где человек был: он вернётся туда же, а не к началу.
 */
@Composable
private fun Places(stacks: TabStacks) {
    NavigationBar {
        for (place in Place.entries) {
            val selected = stacks.place == place.key
            NavigationBarItem(
                selected = selected,
                onClick = { if (selected) stacks.backToRoot(place.key) else stacks.go(place.key) },
                icon = { Icon(painterResource(place.icon(selected)), contentDescription = null) },
                // Подпись в одну строку: на 360 dp пять мест делят экран по 72 dp, и подпись из
                // двух строк полоса обрезала бы (Sm29).
                label = {
                    Text(
                        stringResource(place.label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun NotReadyYet() = EmptyState(text = stringResource(R.string.screen_not_ready))
