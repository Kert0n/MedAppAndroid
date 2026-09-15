package com.kert0n.medapp.app.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.kert0n.medapp.presentation.pack.PackageRecountViewModel
import com.kert0n.medapp.presentation.pack.PackageTransferViewModel
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.medkit.MedKitContentsScreen
import com.kert0n.medapp.ui.medkit.MedKitFormScreen
import com.kert0n.medapp.ui.medkit.MedKitListScreen
import com.kert0n.medapp.ui.pack.PackageCardScreen
import com.kert0n.medapp.ui.pack.PackageFormScreen
import com.kert0n.medapp.ui.pack.PackageRecountScreen
import com.kert0n.medapp.ui.pack.PackageTransferScreen

/**
 * Оболочка приложения: пять мест внизу и содержимое над ними. Где человек стоит и как глубоко —
 * живёт в [TabStacks] и переживает поворот и смерть процесса.
 *
 * **Движения у оболочки нет вовсе: экран просто сменяется** (решение владельца 2026-09-15).
 * Раскладка — как у референса (HomeMedkit-App): содержимое места и панель мест, панель только у
 * мест. Собственная система движения поверх библиотеки, которая ведёт предиктивный жест
 * по-своему, — это борьба с библиотекой, и прошлый заход на ней и кончился (отклонённый PR #33).
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
        // Панель мест — у мест: в глубине человек занят одним делом, и пять соседних комнат
        // ему не нужны. Появляется и исчезает вместе с экраном, без своего движения.
        bottomBar = { if (stacks.screen in PLACES) Places(stacks) }
    ) { padding ->
        NavDisplay(
            entries = stacks.entries(remember(stacks) { screens(stacks) }),
            onBack = stacks::back,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            transitionSpec = { SWITCH },
            popTransitionSpec = { SWITCH },
            predictivePopTransitionSpec = { SWITCH }
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
            // Заполнение по выбору — следующий коммит U2; пока строка ничего не делает.
            onPick = {},
            onSave = model::save,
            onCancel = stacks::back,
            // Количество здесь показано, но не правится: у пересчёта свой экран (H3 №8).
            onRecount = { key.packageId?.let { stacks.go(Screen.PackageRecount(it)) } }
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
            onRecount = { stacks.go(Screen.PackageRecount(key.packageId)) },
            onTransfer = { stacks.go(Screen.PackageTransfer(key.packageId)) },
            onAskToRemove = model::askToRemove,
            onConfirmRemoval = model::remove,
            onDismissRemoval = model::dismissRemoval,
            onBack = stacks::back
        )
    }
    entry<Screen.PackageRecount> { key ->
        val model = hiltViewModel<PackageRecountViewModel, PackageRecountViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Записано — уходим: кончившейся коробке карточки нет, а у оставшейся число покажет она сама.
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        PackageRecountScreen(
            state = state,
            onEdit = model::edit,
            onSubmit = model::submit,
            onCancel = stacks::back
        )
    }
    entry<Screen.PackageTransfer> { key ->
        val model = hiltViewModel<PackageTransferViewModel, PackageTransferViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Переехала или поехала — решение принято, и экран уходит: новое место покажет карточка.
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        PackageTransferScreen(
            state = state,
            onChoose = model::choose,
            onTransfer = model::transfer,
            onBack = stacks::back
        )
    }
    for (place in Place.entries - Place.MED_KITS) {
        entry(place.key) { NotReadyYet() }
    }
}

private val PLACES: Set<NavKey> = Place.entries.map { it.key }.toSet()

/**
 * Экран просто сменяется: ни проявления, ни движения, ни перехода размера. Переход, у которого
 * есть длительность, быстрые «открыть → назад» перебивают на середине: оба экрана живут вместе,
 * а движение каждый раз стартует с другой точки и выглядит по-разному. У мгновенной смены
 * перебивать нечего.
 */
private val SWITCH = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

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

/** Заглушка места — с подложкой, как у настоящего экрана: сквозь неё не просвечивает соседнее. */
@Composable
private fun NotReadyYet() = Scaffold { padding ->
    EmptyState(text = stringResource(R.string.screen_not_ready), modifier = Modifier.padding(padding))
}
