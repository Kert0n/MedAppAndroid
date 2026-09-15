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
import com.kert0n.medapp.presentation.pack.MedKitContentsViewModel
import com.kert0n.medapp.presentation.pack.PackageCardViewModel
import com.kert0n.medapp.presentation.pack.PackageFormViewModel
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.medkit.MedKitContentsScreen
import com.kert0n.medapp.ui.medkit.MedKitFormScreen
import com.kert0n.medapp.ui.medkit.MedKitListScreen
import com.kert0n.medapp.ui.pack.PackageCardScreen
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
            onOpen = { stacks.go(Screen.MedKitContents(it)) },
            onAdd = { stacks.go(Screen.MedKitForm()) },
            // Ища лекарство, человек не помнит, в какой оно аптечке: поиск ведёт в область
            // «везде», то есть в тот же экран без названной полки.
            onSearch = { stacks.go(Screen.MedKitContents()) }
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
    entry<Screen.MedKitContents> { key ->
        val model = hiltViewModel<MedKitContentsViewModel, MedKitContentsViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.medKitId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Полки больше нет — смотреть её содержимое незачем. Дождалась ли она сервера или
        // ушла сразу, видно в списке: там она либо исчезла, либо помечена.
        LaunchedEffect(state.isRemoved) { if (state.isRemoved) stacks.back() }
        MedKitContentsScreen(
            state = state,
            onSearch = model::search,
            onNarrow = model::narrow,
            onOrder = model::order,
            onReset = model::reset,
            onOpen = { stacks.go(Screen.PackageCard(it)) },
            onAdd = { stacks.go(Screen.PackageForm(medKitId = key.medKitId)) },
            onEdit = { stacks.go(Screen.MedKitForm(key.medKitId)) },
            onAskToRemove = model::askToRemove,
            onPickTarget = model::pickTarget,
            onDismissRemoval = model::dismissRemoval,
            onRemove = model::remove,
            onBack = stacks::back
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
        // Заведённая коробка открывается карточкой: человек заводил её, чтобы посмотреть.
        // Поправленная — нет: её карточка и так лежит под формой.
        LaunchedEffect(state.saved) {
            val saved = state.saved ?: return@LaunchedEffect
            stacks.back()
            if (key.packageId == null) stacks.go(Screen.PackageCard(saved))
        }
        PackageFormScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onCancel = stacks::back,
            // Пересчёт — следующий экран набора; пока вести некуда.
            onRecount = {}
        )
    }
    entry<Screen.PackageCard> { key ->
        val model = hiltViewModel<PackageCardViewModel, PackageCardViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Выброшенной коробке карточки нет: уходим туда, откуда пришли.
        LaunchedEffect(state.isRemoved) { if (state.isRemoved) stacks.back() }
        PackageCardScreen(
            state = state,
            onEdit = { stacks.go(Screen.PackageForm(packageId = key.packageId)) },
            // Пересчёт и перенос — следующие экраны набора; пока вести некуда.
            onRecount = {},
            onTransfer = {},
            onAskToRemove = model::askToRemove,
            onConfirmRemoval = model::remove,
            onDismissRemoval = model::dismissRemoval,
            onBack = stacks::back
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
